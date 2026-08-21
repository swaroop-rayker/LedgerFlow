# ADR-0016 — The taxonomy gets a lifecycle, and its hard delete needs a rule the schema cannot give it

- **Status:** Accepted
- **Date:** 2026-08-21
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. Extends the lifecycle ADR-0015 gave
  `ledger_entry` to `category`, `merchant` and `payment_method`.
- **Spec sections touched:** `SPEC.md` §5.5; `CLAUDE.md` §7 ("Destroying ledger
  data")

## Context

S9 gave a committed ledger entry a full lifecycle: soft delete, a bin that
lists it, restore, and a guarded irreversible purge. The taxonomy never got one,
and the gap surfaced while explaining what the Merchants tab's "Hide" button
does.

It is a soft delete. `DefaultMerchantRepository.delete` sets `deleted_at` and
leaves the row, so past entries keep showing the merchant's name — which is
correct and deliberate (§5.5: "a hidden merchant keeps labelling the entries it
was already on"). What is missing is everything after it. There is no list of
hidden merchants, no restore, and no way to remove one for good. **Once hidden,
there is no way back through the UI at all.**

The same is true of the other two, in slightly different words. All three
soft-delete, and the screen calls the one operation three different things:

| Type | Button | What it does | Way back? |
|---|---|---|---|
| Category | "Delete" | `softDelete`, after re-assigning entries | none |
| Merchant | "Hide" | `softDelete`, entries keep pointing at the row | none |
| Payment method | "Remove" | `softDelete`, `payment_method_id` scrubbed off entries | none |

Restore is straightforward. **Hard delete is not**, and the reason is that the
schema does not merely fail to help — it actively hides the mistake:

- `ledger_entry.merchant_id` is `ON DELETE SET NULL`. Destroying a merchant
  therefore does not fail. It succeeds, and silently strips the merchant's name
  off every entry that ever used it.
- `ledger_entry.category_id` and `subcategory_id` have **no foreign key at
  all**. Destroying a category leaves entries holding an id that resolves to
  nothing; the list's `LEFT JOIN` renders them as unfiled.
- `payment_method_id` is `ON DELETE SET NULL` too, but by the time a payment
  method is hidden its soft delete has already cleared the column everywhere,
  so there is nothing left to strip.

In two of the three cases the database will happily do the wrong thing and
report success. So the rule that decides whether a purge may proceed has to live
in code, and it has to be tested, because nothing else will catch it.

There is a second-order version of the same trap. `countForCategory` and
`countForMerchant` both bind `deleted_at IS NULL`. That is right for a soft
delete — it asks "does anything *live* still use this" — but a purge damages
binned entries exactly as much as live ones, and binned entries are restorable
from the bin. A purge that reused those counts would pass its check and quietly
strip labels off rows the user can still bring back.

## Options considered

### Option A — hidden lists and restore, no hard delete

| | |
|---|---|
| Summary | Each section grows a hidden list with a Restore action. Nothing is ever destroyed. |
| Cost | The taxonomy accumulates forever. A user who mistypes a merchant name during ingest gets a row that can be hidden but never removed, and the hidden list becomes the junk drawer. |
| Risk | Low, and it is the honest floor: it closes the reported gap and adds no irreversible operation. But it answers "let me undo that" while leaving "let me get rid of this" unanswered, and the second is why people hide things. |

### Option B — hard delete, letting the foreign keys decide

| | |
|---|---|
| Summary | `DELETE FROM merchant WHERE id = ?` and let `ON DELETE SET NULL` handle the fallout. |
| Cost | For merchants this silently rewrites history — the entry keeps its amount and loses the shop. For categories there is no FK, so entries keep a dangling id instead. Neither reports an error. |
| Risk | Unacceptable, and worth stating plainly because it is the implementation that looks simplest and passes a manual smoke test. The damage is invisible until the user scrolls back far enough to see a month of unlabelled entries. |

### Option C — hard delete behind a reassign-or-block rule in code

| | |
|---|---|
| Summary | Count every entry referencing the row, in both books, **including binned ones**. If the count is zero, destroy it. If not, refuse with `ReassignRequired(n)` so the caller can ask where the references should go, then re-point and destroy in one transaction. |
| Cost | Three purge paths to write and guard, each with a count that must not drift back to the live-only one. A second irreversible operation in an app that had exactly one. |
| Risk | Contained, because the shape already exists: `DefaultCategoryRepository.delete` has run a `ReassignRequired` flow since P1, and `PurgeDeletedEntriesUseCase` established how an irreversible operation is guarded. This is those two put together. |

## Decision

**All three taxonomy types get the lifecycle `ledger_entry` already has: a
hidden list, restore, and a purge that is refused unless nothing references the
row.**

The purge rule is Option C, and three properties make it enforceable rather
than merely intended:

1. **The count includes binned entries.** New `countAllForCategory` /
   `countAllForMerchant` statements omit the `deleted_at IS NULL` predicate that
   their soft-delete siblings carry. The existing statements keep theirs — the
   two questions are genuinely different and both are asked.
2. **Every hard-delete statement binds `deleted_at != 0`**, exactly as
   `purgeDeletedEntry` binds `deleted_at IS NOT NULL`. Only a hidden row can be
   destroyed, so no screen can reach a live one through the purge path even by
   passing an id it should not have had.
3. **One audited door each**, in `TaxonomyUseCases.kt`, enforced by
   `TaxonomySingleWriterTest` on the same source-scanning principle as
   `LedgerSingleWriterTest`. A rule a reviewer has to notice is not an invariant.

**Restore does not get a use case, and that asymmetry is deliberate.**
`LedgerSingleWriterTest` guards `restoreEntry` because un-binning an entry
changes totals somebody has already read. Un-hiding a merchant changes no figure
anywhere — it changes what a picker offers. Guarding it would be ceremony, and
ceremony is what teaches people that the guards are ceremony.

**No schema change, so no v6 and no migration.** Every statement this needs is
expressible against the tables as they stand. The one place that looked like it
needed a schema change does not: `index_merchant_normalized_key` is
`UNIQUE (normalized_key)` with no `deleted_at`, so a hidden "Amazon" blocks
creating a new one — but the right fix is for `createOrGet` to un-hide the row
it already has rather than for the index to permit two (BUG11 below).

## Consequences

**What this makes easy.** Hiding something stops being a decision the user
cannot revisit. It also makes hiding *safer to recommend*: "hide it, you can
always bring it back" is now true, where before it was the trap.

**What this makes hard.** There are now two irreversible operations in the app
instead of one, and the second is reachable from a screen people visit for
routine tidying rather than from a Settings row they open deliberately. The
confirmation carries the weight: `LfDialogEmphasis.Warning`, the row named, and
the same instruction to export first that the bin uses — because the app cannot
back up for them, `.lfbk` being phrase-derived (ADR-0011).

**What we now have to maintain forever.** The two counting statements have to
stay two. If `countAllForMerchant` is ever "simplified" into `countForMerchant`,
the purge check silently narrows to live entries and the failure appears months
later as binned entries that lost their merchant. `TaxonomyPurgeTest` asserts
the binned case directly for that reason.

**A compaction port, extracted.** `compactStorage()` was a `LedgerRepository`
method because the ledger purge was its only caller. A taxonomy purge erases
user data too — a merchant name is a thing the user typed — so it compacts as
well, and a second caller is exactly the point at which the method stops
belonging to `LedgerRepository`. It moves to `StorageMaintenance` in
`:core:domain`. The mechanics are unchanged and still delicate: WAL checkpoint,
then `VACUUM` outside any transaction, and the instrumented test re-opens and
reads the vault afterwards (`CLAUDE.md` §7).

**What would make us revisit this.** A request to purge a taxonomy row *and*
keep the entries readable — "delete this merchant but leave the name on old
entries" — is not a widening of this ADR. It is a request for the name to be
copied onto the entry, which is a denormalisation with its own migration and its
own consequences for merge, and belongs in a new ADR.

## Two defects this closes on the way past

**BUG11 — a hidden merchant's name cannot be used again, and it throws.**
`byNormalizedKey` filters `deleted_at = 0`, so the lookup does not find a hidden
row; the `UNIQUE` index does not filter it, so the write that follows violates
the constraint. With `OnConflictStrategy.ABORT` that is a raised
`SQLiteConstraintException`, not a `TaxonomyResult.Failure` — an uncaught throw
out of a repository whose entire contract is typed refusals.

**There are two doors, and they need different answers.**

- `createOrGet` — reachable in two taps: hide a merchant, then add one with the
  same name. Fixed by **restoring** the hidden row, which is what the person
  typing the name meant; it brings the merchant's aliases and default category
  back with it.
- `rename` — reachable in two taps as well: hide "Big Bazaar", then rename
  "DMart" to "Big Bazaar". It cannot take the same fix, because the row being
  renamed already exists and un-hiding would leave two rows on one key. It
  **refuses**, with a new `TaxonomyError.NameHeldByHiddenRow` rather than
  `DuplicateName` — reporting a duplicate would send the user hunting through a
  list the name is deliberately not in.

The second door was found by an instrumented test failing while trying to *set
up* an unrelated scenario, after the first had been fixed and the bug declared
closed. It is the argument for testing this layer against a real database: no
fake enforces a unique index, so no fake could have failed.

With both closed, `normalized_key` is effectively unique across live *and*
hidden rows — which is what the index always claimed. `restore`'s own clash check
stays as defence in depth, because that property now rests on three methods
agreeing rather than on the schema, and a `.lfbk` restore writes rows directly.

**BUG12 — deleting a category promotes its subcategories.**
`DefaultCategoryRepository.delete` calls
`reparentChildren(id, reassignTo, parentKeyOf(reassignTo))`, and in the
no-entries path `reassignTo` is null — so the children's `parent_id` becomes
null and they become top-level categories the user never created. The comment
directly above the call says the opposite ("Children follow the parent out
rather than becoming orphaned top-level categories"), which is what the code
should have done. Fixed here rather than logged, because a hidden list makes it
load-bearing: if children are silently promoted instead of hidden, restoring a
parent has nothing to restore and the tree comes back a different shape from the
one that was deleted.

## Verification

- `TaxonomySingleWriterTest` (`:core:domain`) fails the build if anything other
  than the three purge use cases calls a `hardDelete*` statement, and asserts
  each use case exists and takes the reassign argument.
- `TaxonomyPurgeTest` (`:core:data`, instrumented) covers the case the schema
  will not: a merchant referenced only by a **binned** entry is refused, and
  after re-assignment the binned entry carries the target merchant rather than
  null. It re-opens and reads the vault after compaction.
- `Bug11_HiddenMerchantNameCanBeReusedTest` and
  `Bug12_HiddenCategoryKeepsItsChildrenTest` are the named regression tests
  Law 7 requires. The first covers **both** doors — the `createOrGet` that
  restores and the `rename` that refuses — plus the counterpart case that keeps
  the first honest: after a purge, the name creates a genuinely fresh row.
- `LedgerIsolationTest` is unchanged and still passes: the re-pointing
  statements a purge uses already bind `:ledger` and are issued once per book.
