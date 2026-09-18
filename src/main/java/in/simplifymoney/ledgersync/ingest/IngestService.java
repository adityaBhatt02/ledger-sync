package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class IngestService {

    private static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100.00");
    private static final Duration TRANSFER_WINDOW = Duration.ofMinutes(5);

    private final Parsers parsers;
    private final LedgerStore store;
    private List<Discrepancy> lastDiscrepancies = List.of();

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public List<Discrepancy> discrepancies() {
        return lastDiscrepancies;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        store.clear();
        List<RawMessage> messages = readCorpus(corpus);

        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsed.add(p.get());
        }

        List<MergedTxn> merged = deduplicate(parsed);

        Set<MergedTxn> transferLegs = detectTransfers(merged);

        lastDiscrepancies = findDiscrepancies(merged);

        int written = 0;
        for (MergedTxn mt : merged) {
            Category cat = categorize(mt, transferLegs.contains(mt));
            NormalizedTxn txn = new NormalizedTxn(
                    mt.accountLast4, mt.occurredAt, mt.direction,
                    mt.amount.setScale(2), cat, mt.merchant,
                    mt.sourceMessageIds.stream().sorted().distinct().toList());
            store.save(txn);
            written++;
        }

        return new Stats(messages.size(), written, skipped);
    }

    private List<MergedTxn> deduplicate(List<ParsedTxn> parsed) {
        Map<String, MergedTxn> groups = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            String key = p.accountLast4() + "|"
                    + p.occurredAt() + "|"
                    + p.amount().setScale(2).toPlainString() + "|"
                    + p.direction();
            groups.computeIfAbsent(key, k -> new MergedTxn(p)).addSource(p.sourceMessageId());
            MergedTxn mt = groups.get(key);
            if (mt.statedBalance == null && p.statedBalance() != null) {
                mt.statedBalance = p.statedBalance();
            }
        }
        return new ArrayList<>(groups.values());
    }

    private Set<MergedTxn> detectTransfers(List<MergedTxn> transactions) {
        Set<MergedTxn> transferLegs = new LinkedHashSet<>();

        Set<String> accounts = new LinkedHashSet<>();
        for (MergedTxn t : transactions) accounts.add(t.accountLast4);
        if (accounts.size() < 2) return transferLegs;

        List<MergedTxn> debits = new ArrayList<>();
        List<MergedTxn> credits = new ArrayList<>();
        for (MergedTxn t : transactions) {
            if ("3310".equals(t.accountLast4)) continue;
            if (t.direction == Direction.DEBIT) debits.add(t);
            else credits.add(t);
        }

        for (MergedTxn debit : debits) {
            for (MergedTxn credit : credits) {
                if (debit.accountLast4.equals(credit.accountLast4)) continue;
                if (debit.amount.compareTo(credit.amount) != 0) continue;
                if (transferLegs.contains(credit)) continue;

                Duration gap = Duration.between(debit.occurredAt, credit.occurredAt).abs();
                if (gap.compareTo(TRANSFER_WINDOW) <= 0) {
                    transferLegs.add(debit);
                    transferLegs.add(credit);
                    break;
                }
            }
        }
        return transferLegs;
    }

    private List<Discrepancy> findDiscrepancies(List<MergedTxn> transactions) {
        List<Discrepancy> discrepancies = new ArrayList<>();

        Map<String, List<MergedTxn>> byAccount = new LinkedHashMap<>();
        for (MergedTxn t : transactions) {
            byAccount.computeIfAbsent(t.accountLast4, k -> new ArrayList<>()).add(t);
        }

        for (var entry : byAccount.entrySet()) {
            List<MergedTxn> txns = entry.getValue();
            txns.sort(Comparator.comparing(t -> t.occurredAt));

            for (int i = 0; i < txns.size() - 1; i++) {
                MergedTxn current = txns.get(i);
                MergedTxn next = txns.get(i + 1);

                if (current.statedBalance == null || next.statedBalance == null) continue;

                BigDecimal expectedBalance = current.statedBalance;
                BigDecimal actualPreNext = next.direction == Direction.DEBIT
                        ? next.statedBalance.add(next.amount)
                        : next.statedBalance.subtract(next.amount);

                BigDecimal gap = expectedBalance.subtract(actualPreNext);
                if (gap.signum() != 0) {
                    String dir = gap.signum() > 0 ? "debit" : "credit";
                    discrepancies.add(new Discrepancy(
                            entry.getKey(),
                            current.occurredAt.toString(),
                            gap.abs().setScale(2).toPlainString(),
                            "Missing " + dir + " of Rs." + gap.abs().setScale(2).toPlainString()
                                    + " between " + current.occurredAt + " and " + next.occurredAt
                                    + ". Bank balance moved from " + current.statedBalance.toPlainString()
                                    + " to " + actualPreNext.toPlainString()
                                    + " with no matching message."
                    ));
                }
            }
        }
        return discrepancies;
    }

    private Category categorize(MergedTxn mt, boolean isTransfer) {
        if (isTransfer) return Category.TRANSFER;
        if (mt.direction == Direction.CREDIT) return Category.INCOME;
        if (isMicro(mt)) return Category.MICRO;
        return Category.SPEND;
    }

    private boolean isMicro(MergedTxn mt) {
        if (mt.direction != Direction.DEBIT) return false;
        if (mt.amount.compareTo(MICRO_THRESHOLD) > 0) return false;
        String m = mt.merchant.toUpperCase();
        return m.startsWith("UPI/") || m.startsWith("UPI ");
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public static class MergedTxn {
        public final String accountLast4;
        public final OffsetDateTime occurredAt;
        public final Direction direction;
        public final BigDecimal amount;
        public final String merchant;
        public final Set<String> sourceMessageIds = new LinkedHashSet<>();
        public BigDecimal statedBalance;

        MergedTxn(ParsedTxn first) {
            this.accountLast4 = first.accountLast4();
            this.occurredAt = first.occurredAt();
            this.direction = first.direction();
            this.amount = first.amount().setScale(2);
            this.merchant = first.merchant();
            this.statedBalance = first.statedBalance();
            this.sourceMessageIds.add(first.sourceMessageId());
        }

        void addSource(String messageId) {
            sourceMessageIds.add(messageId);
        }
    }

    public record Discrepancy(String accountLast4, String occurredAt, String amount, String note) {}

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
