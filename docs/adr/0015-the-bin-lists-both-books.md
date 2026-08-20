# ADR-0015 — The bin lists both books in one list

- **Status:** Accepted
- **Date:** 2026-08-20
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. **Amends `SPEC.md` §5.5's "separate lists"** for one screen.
- **Spec sections touched:** `SPEC.md` §5.5; `CLAUDE.md` §2 Law 2

## Context

Deleting a ledger entry is a soft delete (`deleted_at`), and the owner asked for
the surface that manages those: a bin listing everything deleted, with
per-row selection, restore, erase-selected and erase-all.

One line of the request collides with a standing rule:

> shows all the entries chronologically … (expense or income, can be mixed, no
> need of separation in this page)

`SPEC.md` §5.5 says the opposite, in as many words:

> **Debit ledger** and **Credit ledger** are fully separate: **separate lists**,
> separate category trees, separate analytics screens, separate budgets.

And Law 2 (`CLAUDE.md` §2):

> **Ledgers never mix.** Every query touching `ledger_entry` must filter on
> `ledger`. No SUM, JOIN, or UI element may combine DEBIT and CREDIT into one
> figure.

Read carefully, Law 2 has two clauses and they are not the same clause. The
first is about *statements*: every query binds a ledger. The second is about
*figures*: nothing may combine the two into one number. A list showing rows from
both books, each individually signed, breaks neither — there is no statement
spanning them and no number combining them. What it does break is §5.5's
"separate lists", which is a stronger and more specific promise.

So this is not a question of whether Law 2 can be satisfied — it can be — but of
whether the "separate lists" promise should hold on a screen that is not reading
a ledger at all.

The honest framing: **a bin is storage management, not accounting.** The
question it answers is "what did I throw away, and do I want it back" — a
question about rows in a file. Every other ledger surface answers an accounting
question ("what did I spend"), and for those the partition is the point.

## Options considered

### Option A — one mixed list, two queries underneath

| | |
|---|---|
| Summary | `observeDeleted(ledger)` is called once per book and the results are merged in Kotlin, sorted by `occurred_at`. Each row carries its own `LedgerType` and signs and colours itself. No totals anywhere on the screen. |
| Cost | §5.5's "separate lists" needs amending, and this ADR has to exist so the exception is recorded rather than discovered. One screen now behaves unlike every other. |
| Risk | The precedent. "The bin mixes them" is quotable at the next surface that finds the partition inconvenient — a Dashboard, a search. Mitigated by naming the distinguishing property here: this screen shows no figure derived from more than one row. |

### Option B — `Expenses | Income` segmented control, as the Ledger has

| | |
|---|---|
| Summary | The bin partitions like everything else. The user switches tabs to see the rest. |
| Cost | The task is "find the thing I deleted and put it back". The user usually does not remember which book it was in — that is *why* they are looking. A partition makes them check two places for one item, and "erase all" becomes ambiguous: this book, or both? |
| Risk | Low technically, high in use. It is consistent for consistency's sake on the one screen where the partition carries no meaning: a deleted row is not part of a total, so keeping it separate protects nothing. |

### Option C — one list, sectioned by book

| | |
|---|---|
| Summary | A mixed screen with `Expenses` and `Income` headers, chronological within each. |
| Cost | Chronological ordering is lost across the screen, which is what was asked for. Two sections that are each also chronological is harder to scan than one list that simply is. |
| Risk | The worst of both: it still contradicts "separate lists" (they are on one screen, one scroll) while giving up the benefit that contradiction buys. |

## Decision

**The bin renders one chronological list containing both books, built from one
query per ledger and merged for display. No figure on the screen is derived
from more than a single row.**

The deciding argument is what "separate lists" is *for*. It exists so a user
never sees expenses and income implied to be commensurable — so that no screen
suggests ₹100 spent and ₹100 earned cancel. That risk is carried entirely by
*aggregation*, and there is no aggregation in a bin: no total, no subtotal, no
count that spans books except the number of rows to be erased, which is a count
of files rather than of money. Each row is signed, coloured by its own book, and
stands alone.

Law 2's mechanical clause is untouched and stays enforced: `LedgerIsolationTest`
still fails any statement naming `ledger_entry` without binding `:ledger`, and
still fails any statement naming both views. The merge happens in Kotlin, over
two result sets, in a ViewModel.

It is worth being explicit that this is narrower than it looks: **the carve-out
is "a list with no derived figures", not "the bin is special".** A future bin
that grew a "total deleted" line would be back in breach, and correctly so.

## Consequences

**What this makes easy.** Finding a deleted entry without knowing which book it
was in, which is the normal case. One "erase all" with an unambiguous meaning.
One selection model, one set of actions.

**What this makes hard.** §5.5 now has an exception, and exceptions are quotable.
Anyone proposing a second mixed surface has to argue that theirs also derives no
figure across books — and most proposals will not be able to, because most
screens exist precisely to show a figure.

**What we now have to maintain forever.** This ADR, the amendment in §5.5, and
the property that keeps it true: no total on the bin screen, ever. The bin's
row model (`DeletedEntry`) carries `LedgerType` for exactly this reason, so a
row can never be rendered without its direction.

**What would make us revisit this.** A request for any aggregate on this screen
— "total deleted", "₹ recoverable" — is the trigger, and the answer is not to
widen this ADR but to refuse the aggregate. Equally, if a second surface needs
mixing, that is a sign the partition itself is being questioned and belongs in a
new ADR against ADR-0002 rather than an extension of this one.

## Verification

- `LedgerIsolationTest` (`:core:database`) is unchanged and still passes: it
  fails any statement naming `ledger_entry` without `:ledger`, and any statement
  naming both `debit_entries` and `credit_entries`. The bin's read binds
  `:ledger` and is issued twice.
- `BinViewModelTest` asserts that both books' entries appear in one list, that
  each keeps its own `LedgerType`, and that restore and purge are dispatched
  with the book the row came from — the property that would break first if the
  merge ever lost track of provenance.
- `LedgerSingleWriterTest` guards the two new doors this screen needs
  (`restoreEntry`, `purgeDeletedEntry`) the same way it guards `approve`,
  `softDeleteEntry` and `purgeDeletedEntries`.
