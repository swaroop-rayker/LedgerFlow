# ADR-0002 — DEBIT/CREDIT storage: one partitioned table, read through views

- **Status:** Accepted
- **Date:** 2026-08-13
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none
- **Spec sections touched:** `SPEC.md` §6.1, §1.2 (P2); `CLAUDE.md` §2 Law 2

## Context

Law 2 says the debit and credit ledgers never interact: no query, no `SUM`, no `JOIN`, no UI element may combine them into one figure. They are two books that happen to live in one app.

The question is how the storage layer expresses that. The kickoff framed it correctly: the interesting axis is not ergonomics but **how the isolation invariant is mechanically enforced rather than merely intended**. A convention that a reviewer has to notice is not an invariant.

Four other tables reference an entry, and they turn out to decide this: `line_item.entry_id`, `attachment.entry_id`, `pending_transaction.approved_entry_id`, and `daily_rollup` (aggregate, no FK). This ADR must be read as a decision about the *whole* schema, not just one table.

## Options considered

### Option A — Separate `debit_entry` and `credit_entry` tables

| | |
|---|---|
| Summary | Two physically distinct tables with identical column sets. |
| Cost | Every schema change, index, migration and DAO written twice. |
| Risk | Referential integrity — see below. |

Isolation is structural and total: there is no query you can accidentally write that sums both. Combining them requires an explicit `UNION`, which is impossible to type by accident and trivial to grep for. On the stated criterion — mechanical enforcement — this is the strongest option available, and it deserves to be taken seriously rather than dismissed for being verbose.

The problem is what it does to everything that points at an entry. **SQLite foreign keys reference exactly one parent table.** With two entry tables, `line_item.entry_id` cannot be a foreign key. The available escapes are all bad:

1. **Duplicate the children too** — `debit_line_item` / `credit_line_item`, then the same for attachments and pending links. The duplication cascades through the schema rather than stopping at one table.
2. **Two nullable FK columns** (`debit_entry_id`, `credit_entry_id`) plus a `CHECK` that exactly one is non-null. This works, but doubles the indices on every child table, makes every child query a two-branch affair, and Room models it poorly.
3. **Drop the foreign key** and enforce the link in code. This forfeits `ON DELETE CASCADE` and, more seriously, forfeits `PRAGMA foreign_key_check` — which `CLAUDE.md` §7 mandates after **every** migration as the abort condition. Deleting the check that catches migration corruption, in the name of preventing ledger mixing, trades a convention risk for an integrity risk.

Option A also doubles the migration surface permanently. Law 4 requires every schema change to ship a `Migration` plus a `MigrationTest`; two tables means two of each, forever, and BUG8 is precisely the class of bug that lives in migration count.

### Option B — One `ledger_entry` table, mandatory `ledger` partition column

| | |
|---|---|
| Summary | Single table; `ledger TEXT NOT NULL` is the partition key on every query. |
| Cost | Isolation is enforced by tooling rather than by physics. |
| Risk | A query that omits the `ledger` filter compiles and runs. |

Referential integrity is trivial — one parent table, real foreign keys, working `ON DELETE CASCADE`, and `PRAGMA foreign_key_check` stays meaningful. Migrations are written once.

The honest weakness is that `SELECT SUM(amount_minor) FROM ledger_entry` is a valid statement that silently violates Law 2. That risk is real, but it is *addressable*, whereas Option A's integrity gap is structural.

### Option C — Option B plus read-only `@DatabaseView`s

Room's `@DatabaseView` lets `DebitEntries` and `CreditEntries` be declared as views with the `WHERE ledger = ...` predicate baked in at the schema level. DAOs read from the views; nothing reads the base table directly. Views are part of the exported schema JSON and are validated by Room like any other object.

This recovers most of Option A's mechanical safety at Option B's cost, because for **read** paths the filter is physically not omittable — it is part of the object being queried.

## Decision

**Option B, hardened with Option C's views: a single `ledger_entry` table partitioned by a mandatory `ledger` column, with all DAO reads going through per-ledger `@DatabaseView`s.**

The decisive argument is the polymorphic foreign key. Option A's isolation guarantee is genuinely stronger, but it is paid for by removing foreign keys from `line_item` and `attachment` — and `PRAGMA foreign_key_check` after every migration is one of the few automated things standing between this project and BUG8. Weakening migration integrity to strengthen a query-shape invariant is the wrong trade, because migration corruption is silent and unrecoverable while a missing `ledger` filter is detectable by tooling.

Isolation is then enforced at four independent levels, none of which relies on a reviewer noticing:

| Level | Mechanism | Catches |
|---|---|---|
| Schema | `ledger TEXT NOT NULL CHECK (ledger IN ('DEBIT','CREDIT'))` | null/garbage partition values |
| Physical | every index leads with `ledger` — `(ledger, local_date DESC)` etc. | a debit query never traverses credit rows; makes the partition real in the B-tree, so performance matches Option A |
| Read path | `@DatabaseView DebitEntries` / `CreditEntries`; DAOs never name the base table in a `SELECT` | omitted filters on all read queries |
| Test | `LedgerIsolationTest` reflects over every `@Query` in every DAO and fails if a statement names `ledger_entry` without binding a ledger discriminator | raw queries, future DAOs, anything that bypasses the views |

Writes are already funnelled through `ApproveTransactionUseCase` by Law 1, so the write path is a single audited function rather than a surface.

This requires **no change to the schema in `SPEC.md` §6.1** — it already describes exactly this shape. The ADR ratifies it and adds the enforcement machinery.

## Consequences

**What this makes easy.** Real foreign keys and cascades. One migration per schema change. One set of indices. Adding a third ledger-like partition later (if that ever became sane) is a column value, not a table.

**What this makes hard.** `SELECT ... FROM ledger_entry` without a predicate remains syntactically legal. The four enforcement levels make it hard to do accidentally, but they do not make it impossible the way two tables would. This is the accepted cost, stated plainly.

**What we now have to maintain forever.** The two views and their entries in the committed schema JSON; the `LedgerIsolationTest` reflection harness; the discipline that new DAO reads target views. When a migration changes `ledger_entry`, the views must be dropped and recreated in the same migration — Room will fail schema validation otherwise, which is the desired failure mode.

**What would make us revisit this.** If `LedgerIsolationTest` ever has to be weakened or exempted to let a legitimate query through, that is the signal that the partitioned model is fighting a real requirement. Reopen rather than adding an exemption list.

## Verification

- `LedgerIsolationTest` — reflection over all DAO `@Query` annotations; blocking in `unit-test`.
- Room schema validation of the two views, via the committed schema JSON and `guard-schema.sh`.
- An instrumented assertion that seeds both ledgers and confirms every view and DAO read returns rows of exactly one `ledger` value.
