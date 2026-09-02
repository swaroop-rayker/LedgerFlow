# ADR-0006 — Rollups recompute touched buckets; reconciliation is the same code over a wider range

- **Status:** Accepted
- **Date:** 2026-09-02
- **Deciders:** owner
- **Supersedes / Superseded by:** none. Builds on ADR-0018 (line grain) and ADR-0002 (ledger partitioning).
- **Spec sections touched:** `SPEC.md` §5.6, §5.7, §6.1, §7 (restore), §11; `CLAUDE.md` §2 Laws 1 & 2, §7, §8

## Context

`SPEC.md` §5.6 already states a hybrid: `daily_rollup` "rebuilt incrementally by `RollupWorker` on every ledger write and reconciled nightly." That sentence settles *that* there are two mechanisms. It leaves three things open, and each has a failure mode shaped like wrong money on a screen:

1. **What the incremental step actually does** — apply a signed delta, or recompute?
2. **What reconciliation does when it disagrees** with the incremental state. §5.6 does not say, and this is the question the kickoff singles out.
3. **Which writes are ledger writes.** §5.6 says "every ledger write" as though there were one. There are five.

### There are five doors, not one, and only three of them matter

`CLAUDE.md` §7 names four doors into `ledger_entry`, all guarded by `LedgerSingleWriterTest`, plus the restore path it permits by name:

| Writer | In a transaction today? | Changes rollups? |
|---|---|---|
| `approve` | ✅ `database.withTransaction { … }` | ✅ adds |
| `softDeleteEntry` | ❌ bare DAO `UPDATE` | ✅ removes |
| `restoreEntry` | ❌ bare DAO `UPDATE` | ✅ **re-adds — past totals change again** |
| `purgeDeletedEntry` / `purgeDeletedEntries` | ❌ (+ `VACUUM` outside a transaction) | ❌ **no-op** |
| `DatabaseBackupManager` restore | ✅ single transaction | ✅ rebuilds everything |

The purge row is the useful finding. `debit_entries` / `credit_entries` filter `deleted_at IS NULL`, so rollups are built from live rows only; the purge statements bind `deleted_at IS NOT NULL`, so every row they destroy was **already absent from the rollups**. Purge — the one irreversible operation in the app, the one wrapped in `VACUUM` and a `Warning` dialog — needs no rollup work at all. That is one fewer place to get this wrong, and it is worth writing down because the reflex is to assume the destructive path is the dangerous one here. It is not; **`restoreEntry` is**, because it silently changes a figure the user already read.

Two of the three doors that *do* matter are currently bare `UPDATE`s outside any transaction. Whatever this ADR chooses, they have to become transactional, because a rollup that updates in a second transaction can be interrupted between the two.

### Law 1 is not directly at stake, but the guard is

Law 1 governs `ledger_entry`. `daily_rollup` is a different table, and nothing here proposes a new writer to `ledger_entry` — reconciliation reads it and never writes it. But the kickoff is right that a new write path deserves the same treatment, so this ADR carries its own single-writer guard for `daily_rollup` (see Verification).

## Options considered

### Option A — SQLite triggers on `ledger_entry` / `line_item`

| | |
|---|---|
| Summary | `AFTER INSERT/UPDATE/DELETE` triggers maintain `daily_rollup` in the database. Room cannot express triggers, so they are raw SQL in the migration and in `onCreate` — a pattern `SPEC.md` §6.0 already sanctions for partial indices and `CHECK`s. |
| Cost | Invisible to every Kotlin test and to `LedgerSingleWriterTest`, which scans *source*. Debuggable only by reading a migration. Duplicated in two places (migration and `onCreate`) that can drift. |
| Risk | **Fatal, and specific: line-grain attribution is order-dependent inside the approval transaction.** `insertEntryWithLineItems` inserts the entry, *then* the lines. An `AFTER INSERT ON ledger_entry` trigger runs when the entry has zero line items and would file the whole ₹1,000 under the entry's category — which, for an itemised entry, ADR-0018 says does not exist. Getting this right needs triggers on `line_item` too, plus a rule for the non-itemised case, plus correct behaviour when a line is inserted for an entry whose trigger already fired. That is a state machine written in SQL, spread across a migration, with no unit test that can reach it. |

Rejected. Not because triggers are unfashionable, but because ADR-0018's grain makes the trigger's firing point wrong by construction.

### Option B — Worker-driven rebuild only

| | |
|---|---|
| Summary | `RollupWorker` rebuilds the table periodically; no in-transaction work at all. |
| Cost | Trivial to implement and trivially correct. |
| Risk | The user approves a transaction from the Inbox, opens Analytics, and their spend has not moved. Nothing is wrong with the data and the app looks broken. This is the same class of defect as BUG10 (a list that loads once and never changes) and the same class of user reaction. |

Rejected as the sole mechanism. Retained as the reconciliation half.

### Option C — In-transaction **signed delta** + nightly reconciliation

| | |
|---|---|
| Summary | Each ledger write computes `+sum/-sum, +1/-1` against the affected buckets, in the same transaction. The canonical hybrid. |
| Cost | Small writes, obviously fast. |
| Risk | **A delta applied twice, or lost once, is permanent and undetectable.** There is no self-correcting property: the arithmetic is right at every step and the total is wrong forever, until a nightly pass happens to notice. Every bug in this shape survives to production and presents as "my October total is ₹340 off", which is unreproducible and unfixable by the user. |

### Option D — In-transaction **recompute of the touched buckets** + nightly reconciliation over all buckets

| | |
|---|---|
| Summary | A write does not adjust its bucket; it **deletes and re-aggregates every `daily_rollup` row for the `(local_date, ledger)` pairs it touched**, straight from `ledger_entry` ⋈ `line_item`. Reconciliation is the identical routine with the date range widened to "everything". |
| Cost | One `DELETE` + one `INSERT … SELECT … GROUP BY` over a single day of one book, per write. A day holds a handful of entries; the index leads with `ledger` (ADR-0002) and `local_date` follows. |
| Risk | Marginally more work per approval than Option C. If a day ever held thousands of entries this would be the wrong shape — it does not, and a note in Consequences records the trigger. |

## Decision

**Adopt Option D. A ledger write recomputes the `(local_date, ledger)` buckets it touched from the base tables, inside the same transaction as the write. The nightly `RollupWorker` runs the same routine over every date. On disagreement, the base tables win, unconditionally — `daily_rollup` is repaired and `ledger_entry` is never written.**

Three arguments, in order of weight.

**1. Idempotence is the whole point.** Option C's delta is correct only if applied exactly once; Option D's recompute is correct however many times it runs, and is correct even if the *previous* write's rollup work was wrong. That converts the entire class of "rollup drift" bugs from permanent and silent into self-healing on the next write to that day — and permanent-and-silent is the failure mode `CLAUDE.md` §7 exists to legislate against everywhere else in this codebase.

**2. Reconciliation stops being a second implementation.** Under Option C, the nightly pass is a *different* algorithm (full aggregate) checked against the incremental one, and any disagreement is ambiguous: which of the two is wrong? Under Option D they are the same function with a different date range, so the nightly pass cannot disagree about *method*, only about *staleness* — and staleness has exactly one correct resolution. A reconciliation that can only be right is worth more than a reconciliation that is a second opinion.

**3. It is the only option that survives ADR-0018 without a special case.** The recompute is one `GROUP BY` that reads line grain where lines exist and entry grain where they do not — the same expression that must be written anyway to build the table. Options A and C both need that expression *plus* a delta path that agrees with it.

### What reconciliation does when it disagrees — the part §5.6 hand-waves

**The base tables are the truth and there is no case in which they are not.** `daily_rollup` contains no information absent from `ledger_entry` and `line_item`; it is a cache with a primary key. Therefore:

- **A disagreement is by definition a rollup bug, never a ledger bug.** Reconciliation overwrites the rollup. It never writes, corrects, back-dates, or "reconciles toward" `ledger_entry` — that would be a fifth writer and a Law 1 violation, and there is no scenario in which the derived table knows something the source does not.
- **It is not silent.** Repairing without recording means a systematic incremental bug is masked by the very mechanism meant to catch it, and nobody ever learns the incremental path is broken. The pass records `rollupLastReconciledAt` and `rollupBucketsRepaired` into `app_meta`. A non-zero repair count on a healthy install is a bug report waiting in the diagnostics screen (`SPEC.md` §11, P5).
- **It is not user-facing.** A self-healing condition that has already healed must not produce a notification or a banner. The user sees corrected numbers and nothing else. This is the opposite of the listener-health banner (ADR-0020), and deliberately so: that banner exists because *only the user* can fix the condition. Here nobody needs to do anything.
- **It repairs the whole table, not a window.** A 5Y ledger is a few thousand rows and one `GROUP BY` scan; a windowed pass would leave old drift permanently unrepaired for the sake of an optimisation nothing needs.

### Consequential decisions that fall out of this

- **`softDeleteEntry` and `restoreEntry` become `@Transaction` DAO methods** that update the row and recompute its bucket together. They are bare `UPDATE`s today. This is a change to shipped code, in P3's scope, and it is the only way "in the same transaction" can be true for three of the doors instead of one.
- **Purge does nothing to rollups**, per the table above. Stated explicitly so a future reader does not add a "safety" recompute there and make the `VACUUM` path longer than it needs to be.
- **Restore rebuilds rollups as its final step**, inside the restore transaction. A restored install must open onto correct analytics, not onto empty charts waiting for a nightly worker.
- **`daily_rollup` is excluded from the `.lfbk` payload — and the exclusion must be explicit.** `ExportCoversEveryTableTest` asserts `containsExactlyElementsIn(schemaTableNames())` against the committed schema JSON, with no exclusion mechanism, so **v9 will turn that test red** the moment `daily_rollup` and `budget` appear in `9.json`. `budget` is user intent and nothing can reconstruct it — it joins the payload, no argument. `daily_rollup` is derived, is likely to be the largest table in the database by row count, and would be serialised as uncompressed JSON into a file the user carries around; it should not be in the backup, and restore rebuilding it is strictly better than restore carrying it. **That test therefore needs a named, reasoned exclusion set, and this ADR is what justifies the entry.** This is scope the v9 commit did not obviously carry, and it is flagged here rather than discovered during it.

## Consequences

**What this makes easy.** A rollup bug heals instead of accumulating. Reconciliation is testable by corrupting a bucket and running the pass — which is exactly the named test the P3 definition of done asks for. Analytics is correct the instant an approval commits, because the recompute is in the approval's transaction.

**What this makes hard.** Every approval now does a small `GROUP BY` before it commits, so the approval transaction is measurably longer than it is today; the ≤1.5 s SMS→notification budget is unaffected (that path writes `pending_transaction`, not the ledger), but the Inbox approve tap should be measured, not assumed. Two existing repository methods change shape. And the recompute expression — the `GROUP BY` that implements ADR-0018's grain — becomes the single most important query in the analytics feature: if it is wrong, it is wrong identically in both mechanisms, and no amount of reconciliation will notice. It gets its own tests against hand-computed fixtures, not against itself.

**What we now have to maintain forever.** One recompute routine and its fixtures; `rollupLastReconciledAt` / `rollupBucketsRepaired` in `app_meta`; the `daily_rollup` exclusion in `ExportCoversEveryTableTest` with its reason; and the single-writer guard below.

**What would make us revisit this.** (a) A day's recompute measurably delaying the approve tap — the trigger is a real measurement on the device, not a suspicion, and the fix is Option C's delta with Option D retained as the nightly pass. (b) A rollup dimension that is *not* derivable from the base tables ever being proposed — at that point `daily_rollup` stops being a cache, this ADR's central premise fails, and the backup exclusion has to be revisited in the same breath. (c) `SPEC.md` §5.6 gaining a rollup grain finer than a day.

## Verification

- **`RollupSingleWriterTest`** — source-scanning, in the shape of `LedgerSingleWriterTest`: only the rollup writer may name the `daily_rollup` insert/delete statements, and no source outside it may reference the table. A fifth ledger door added later without rollup work fails a companion assertion that each of `approve` / `softDeleteEntry` / `restoreEntry` reaches the recompute.
- **`RollupReconciliationRepairsCorruptedBucketTest`** — deliberately corrupt a bucket's `sum_minor`, run the pass, assert the value is restored, assert `rollupBucketsRepaired` is 1, and assert `ledger_entry` is byte-identical before and after. That last assertion is the one that guards "the base tables win".
- **`RollupMatchesBaseTablesTest`** — property-style: for a generated ledger, every rollup row equals the aggregate computed independently from `ledger_entry` ⋈ `line_item`. Run after approve, after soft delete, after restore, and after purge (which must change nothing).
- **`LedgerIsolationTest`** already fails any statement naming `ledger_entry` without a ledger discriminator; every rollup statement binds `ledger` because it is in the primary key (Law 2, `SPEC.md` §6.1).
- **`BackupRestoreRoundTripTest`** gains a budget assertion (§5.7 rows survive) and a rollup assertion (rollups are rebuilt, not restored).
