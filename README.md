# ledger-sync

Simplify Money **Software Engineering Intern (Backend, Java)** take-home submission.

---

## Quick start

```bash
# Compile and self-check (no network, no database)
./verify.sh

# Full pipeline via Gradle
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"

# Run tests
./gradlew test

# Document store (requires Docker)
docker compose up -d
./gradlew run --args="backfill"
./gradlew run --args="check"
```

---

## What I built

### Task 2 — The build

Processes 522 raw bank SMS and email messages into a deduplicated, categorized ledger.

**Results against corpus-a-totals.json:**

| Account | Expected txns | Produced | Balance match |
|---------|--------------|----------|---------------|
| 4821    | 146          | 145      | 7,500.00 gap  |
| 9075    | 91           | 91       | exact         |
| 3310    | 20           | 20       | n/a           |
| **Total** | **257**    | **256**  |               |

Account 9075 matches perfectly. Account 4821 is off by one transaction (Rs.7,500) because the corpus has no SMS or email for a debit that the balance chain proves happened on 29 Jul between 11:53 and 17:06. This is reported in `reconciliation.json`.

**Category totals (4821+9075, verified):**
- INCOME: 142,791.16 ✓
- MICRO: 4,443.85 ✓  
- TRANSFER: 62,000.00 ✓
- SPEND: 126,126.49 + 3310 spend ✓

### Task 3 — The incident

`Amounts.first()` regex `[0-9,]+\.[0-9]{2}` required two decimal places. "Rs.5" has no decimal in the amount — the regex skipped it and matched "Rs.92,213.10" (the available balance). Fix: `[0-9,]+(?:\.[0-9]{1,2})?` makes decimals optional. See `incident/incident-note.md` for the 5-line note.

**Blast radius:** Every message where the bank wrote the amount without decimal places — "Rs.5", "Rs 8,000", "INR 18,000", "Rs.25", etc. All of these extracted the stated balance instead of the actual amount.

**Why tests were green:** `AmountsTest` only tested amounts that already had two decimal places ("Rs.2,499.50", "INR 333.33"). No test ever checked an amount without decimals.

### Task 4 — Document store

**Choice: MongoDB.** Reasons:
1. The three access patterns (account+month, category totals, message→transaction) map naturally to MongoDB queries with compound indexes
2. Simpler local setup (single `docker compose up` vs DynamoDB Local + AWS SDK)
3. No partition/sort key constraints to navigate — schema flexibility suited the exploratory stage

**Document model:**
- `transactions` collection: one document per unique transaction, keyed by `dedup_key` (account|date|amount|direction), with compound index on `(account_last4, occurred_at desc)` for Q1
- `message_index` collection: maps each `message_id` to its `dedup_key` for O(1) Q3 lookups

**Examined vs returned at 100k transactions (estimated):**
| Query | Examined | Returned |
|-------|----------|----------|
| Q1: account+month | ~300 | ~300 |
| Q2: category totals | ~all for account | ~all for account |
| Q3: message→transaction | 1+1 | 1 |

Q1 uses the compound index — only documents matching the account and date range are examined. Q3 does two point lookups (message_index → transactions). Q2 scans all transactions for one account; an aggregation pipeline with a `category_totals` materialized view would eliminate this, noted as future work.

---

## Decision log

1. **Deduplication key: (account, occurred_at, amount, direction).** Rejected using message body hashes because the same transaction produces different bodies in SMS vs email. Rejected using merchant — banks sometimes format it differently across channels.

2. **Transfer detection: matching debits and credits across accounts within 5 minutes.** The IMPS/P2A/PARAG KAPOOR transfers all had 1-2 minute gaps. 5 minutes is conservative enough to avoid false positives while catching slightly delayed confirmations. Rejected merchant-name matching — the same transfer uses slightly different descriptions on each account.

3. **MICRO threshold: UPI debit ≤ Rs.100.** Per the assignment spec. Identified UPI transactions by merchant name prefix "UPI/" or "UPI ". The UPI MANDATE VERIFY (Rs.0.50) is a real bank debit and is included as MICRO.

4. **E-mandate as a transaction.** "E-mandate! Rs.649.00 will be deducted" is the only evidence this debit happened — there's no separate debit SMS and an email confirms it. The balance chain validates the deduction. Without it, account 4821 would be off by 649 more.

5. **Credit card (3310) reconciliation noise.** The "Avl Limit" on a credit card changes due to bill payments and statement credits that don't appear as individual transaction messages. These are reported as discrepancies because they ARE things the ledger cannot account for, but they're expected for credit cards.

6. **MongoDB over DynamoDB.** Simpler Docker setup, no AWS SDK dependency, flexible schema. DynamoDB would be better at scale (predictable latency, partition key design), but for this assignment MongoDB gets us running faster.

7. **Clearing the store before each ingest.** The V2__seed.sql has duplicate legacy rows (same SWIGGY transaction 3 times). Rather than complex upsert logic in SQL, I clear and re-insert from the corpus each time. The seed data represents pre-corpus transactions that the Backfill handles for MongoDB.

8. **The 7,500 reconciliation gap.** The balance on account 4821 drops by 7,500 between two consecutive messages (29 Jul 11:53 IRCTC → 29 Jul 17:06 STATIONERY) with no SMS/email in between. This is a genuine missing message, not a bug. The expected count (146) includes it; my ledger can only evidence 145.

---

## What the data made me decide

- **Amounts without decimals are common.** "Rs.5", "Rs 20", "Rs 8,000", "INR 18,000", "Rs.25" — the original regex missed all of them. This wasn't an edge case; it was a whole class of messages.
- **The re-upload batch (lines 338-522) is a complete re-read.** Every message in it has a twin in lines 1-337 with identical body text but different message_id and received_at. Deduplication by transaction attributes (not message_id) handles this cleanly.
- **NEFT INWARD SELF on 9075 is not a transfer between the user's tracked accounts.** Despite the "SELF" label, there's no matching debit on 4821. It's income from an untracked source.
- **IMPS/P2A/RAHUL SHARMA is not a transfer.** Only one leg (4821 debit) appears — no matching credit on 9075. It's a payment to someone outside the user's accounts.

---

## AI disclosure

**Tools used:** Claude (Anthropic) for code generation, analysis of the corpus data patterns, and regex design.

**One concrete case where AI was wrong:** Claude initially suggested that "NEFT INWARD SELF" credits on account 9075 should be classified as TRANSFER because of the "SELF" keyword. I traced the balance chain and confirmed there is no matching debit on account 4821 for these INR 18,000 credits — they come from a source outside the corpus. I overrode the AI's suggestion and categorized them as INCOME. The AI assumed "SELF" meant self-transfer between the user's tracked accounts, but it actually means the sender's name/reference contained "SELF" on the bank's side.

---

## What's unfinished

- **Q2 (category totals) scans all docs for an account.** A materialized `category_totals` collection updated on each write would make this O(1) instead of O(n). Ran out of time.
- **No deployed URL.** The service runs locally via Gradle and Docker.
- **Reconciliation for credit card is noisy.** The balance-chain approach works for savings accounts but produces false positives on credit cards because Avl Limit changes include billing events not represented as transaction messages.
- **The 100k benchmark numbers are estimates.** Would need to seed 100k transactions and run explain plans against the actual MongoDB instance. The index design supports it, but I didn't have time to generate the test data.
