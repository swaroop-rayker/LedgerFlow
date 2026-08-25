# ADR-0018 — An itemised entry files at line grain, not entry grain

- **Status:** Accepted
- **Date:** 2026-08-25
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none
- **Spec sections touched:** `SPEC.md` §5.4, §5.5, §5.6, §5.7, §6.1; `CLAUDE.md` §2 Laws 1–3

## Context

One payment at a shop that sells across categories is not one category of
spend. A ₹1,000 bill at Reliance Fresh or Walmart is routinely ₹600 of
groceries, ₹300 of household and ₹100 of toys, and the user has exactly one
transaction to record it against — by cash at the till, or by a card whose SMS
alert reports nothing but the total. Filing that as "₹1,000 groceries" is not a
rounding error in the analytics; it is the analytics being wrong about the
largest single thing it is asked to explain.

The schema has been ready for this since v1. `line_item` carries
`category_id`, `subcategory_id`, `quantity_milli` and `unit_price_minor`, and
`ApproveTransactionUseCase` already writes them. What has never been decided is
what those columns *mean* alongside `ledger_entry.category_id`, because until
now nothing wrote them: the entry form's line editor collected a name and an
amount and no category at all.

So the decision is not "should lines carry categories" — they already can — but
**what an entry's own category is once its lines carry theirs.** Three things
read `ledger_entry.category_id` today or will at P3:

- the Ledger list, which renders one category name per row;
- `daily_rollup` (§5.6), whose key includes `category_id` and which every chart
  reads;
- budgets (§5.7), which are per-category and per-subcategory.

Two constraints bound every option. Law 2: the two books are disjoint, so a
line's category must belong to its entry's ledger, and nothing in the schema
enforces that — `line_item` has no foreign key to `category` at all. And §5.4's
existing rule, which this must not contradict: an entry whose lines do not sum
to its total is *saved*, with the difference written as an `UNALLOCATED` line,
so the parts always add up to the whole.

## Options considered

### Option A — the entry keeps a category; lines are decoration

| | |
|---|---|
| Summary | Status quo plus per-line categories that only the entry detail screen reads. `daily_rollup`, budgets and the Ledger list continue to use `ledger_entry.category_id`. |
| Cost | Nothing to build. Nothing downstream changes. |
| Risk | The feature does not work. The user itemises a Walmart bill and every chart still says ₹1,000 of groceries, which is the exact complaint that motivated the feature. Worse than not shipping it, because the breakdown *looks* recorded. |

### Option B — the entry stores its dominant line's category, derived at write

| | |
|---|---|
| Summary | The largest-value line's category is copied onto `ledger_entry.category_id` at approval. Lines remain the detailed truth; the entry-grain value exists so every current reader keeps working untouched. |
| Cost | Zero change to the Ledger list, the repeat-expense chips, or any P3 rollup that has not been written yet. |
| Risk | Two fields that must agree, which is the pattern this codebase removes on sight — `local_date` is derived inside the repository *precisely* so it cannot disagree with `occurred_at`. Here the derived value cannot be kept honest: edit a line at P4 and the dominant category changes, and a rollup fed at entry grain still reports ₹1,000 of groceries. It buys compatibility by writing down something untrue. |

### Option C — the entry files nothing; lines own it

| | |
|---|---|
| Summary | An itemised entry stores `category_id = NULL` and `subcategory_id = NULL`. `line_item` is the only place a category appears for such an entry. Rollups and budgets read line grain; the Ledger list renders a row with no single category as its lines' categories ("Groceries +2"). |
| Cost | The Ledger list's read must learn to describe a row whose category is absent — today `LedgerListRow` projects one `category_name` from a join. `daily_rollup` must be fed per line at P3, and its `txn_count` becomes ambiguous for an itemised entry. |
| Risk | Every screen that assumed "an entry has a category" now has a second case. The compensation is that the case is *real* — a mixed bill genuinely has no single category — so the code stops asserting something false rather than starting to. |

### Option D — entry category as a fallback, lines override it

Rejected in one line, but worth naming because it is the intuitive compromise:
the field then means "the category" on a simple entry and "the category of
whatever I did not itemise" on a complex one, and a partially itemised bill
attributes its remainder to the entry category silently. One column, two
meanings, and the difference is invisible at the call site.

## Decision

**An itemised entry stores no entry-level category. `line_item.category_id` and
`line_item.subcategory_id` are the only filing such an entry has.** Option C.

The argument that decided it is Option B's, inverted. B is genuinely attractive
— it is the only option that changes nothing downstream — and it fails on the
one property that matters: it requires the app to write down a category that is
not true of the entry, and then to keep that untruth in sync forever. The
moment a line is edited (P4's OCR review, or any later edit path) the derived
value is stale, and nothing about the row says so. Option C has a real cost and
it is a cost in *display code*, which is recoverable; B's cost is in stored
data, which is not.

The decision is not close, but its blast radius is larger than it looks: it
changes what "an entry's category" means everywhere, which is why it is an ADR
and not a commit message.

Two things follow immediately and are part of this decision rather than
consequences of it:

- **Line filing is validated exactly as entry filing is.** A line's category
  must exist, must be live, and must belong to the entry's ledger; a line's
  subcategory must be a child of that line's category, and a subcategory
  without a category is refused. These run inside the approval's transaction
  alongside the entry-level rules, for the reason §6.1.1 gives — a check
  outside it can be invalidated by a soft-delete landing before the insert.
- **The `UNALLOCATED` remainder is unchanged and uncategorised.** §5.4 already
  says an unbalanced entry is saved rather than refused; a partially itemised
  bill is the ordinary case of that, not a new one. The remainder row carries no
  category because the user has not said what it is.

## Consequences

**What this makes easy.** The user's actual question — "what did I spend on
electronics" — becomes answerable for the first time, including for card and
UPI payments where the bank only ever reports a total. Analytics at line grain
is already a stated §5.6 dimension ("line-item vs entry grain"); this is what
makes it mean something. It also gives the P2 Inbox and the P4 OCR review the
same target: a captured total plus a user-supplied breakdown is now the normal
shape of an entry, not a special case for receipts.

**What this makes hard.** The Ledger list has to render a row with no category.
`LedgerListRow` projects a single `category_name` off a join today, so this
needs a read-path addition — the cheapest honest form is a per-entry distinct
category count and one name, rendered as "Groceries +2". Any screen that assumes
an entry has a category acquires a second case, and there will be more of those
at P3 than there are today.

`daily_rollup` (§5.6) must be fed per line item, and its `txn_count` becomes
ambiguous: one entry contributing to three category rows is one transaction, not
three. **That is left open for P3 deliberately** — it is a question about a table
that does not exist yet, and answering it now would be inventing a constraint
for code nobody has written. It is recorded in §5.6 so P3 cannot miss it.

Budgets (§5.7) must read line grain too, or a ₹400 kettle inside a grocery bill
lands in the grocery budget — which is the original defect wearing a different
hat.

**What we now have to maintain forever.** The line-filing rules in
`LedgerApprovalRules` and their instrumented suite; the "no entry category when
itemised" invariant; and the Ledger list's mixed-category rendering. Also this
ADR's boundary: a *non*-itemised entry still files at entry grain and always
will, so both paths stay live and both need to keep working.

**What would make us revisit this.** A measurement at P3 showing that
line-grain rollups cannot meet §11's 300 ms budget for a 5Y query would reopen
it — the fallback would be a denormalised per-entry summary column, which is
Option B admitted as a cache rather than as truth, and it would need its own
ADR saying so. Nothing about the UI would reopen it; a display problem is not a
reason to store a wrong number.

## Verification

- `LineItemFilingTest` (`:core:data`, instrumented) is the enforcement. It pins
  the scenario (one payment, two categories, entry category null), every
  refusal including both directions of the Law 2 check, and that a refused
  approval leaves neither an entry nor a partial set of lines behind.
- `MoneyQuantityTest` (`:core:model`) pins the `unit price × quantity`
  arithmetic this depends on, in integers only, including the rounding a
  fractional quantity forces and where the residue goes.
- `LedgerIsolationTest` and `LedgerSingleWriterTest` are unchanged and still
  apply: this adds no new door into `ledger_entry`, and the approval remains
  the only writer.
- `SPEC.md` §5.4 and §5.6 record the rule and the open `txn_count` question, so
  P3 meets it as a stated decision rather than as a surprise in the data.
- `LedgerMappersTest` (`:core:data`, JVM) and `ItemisedEntryListRowTest`
  (instrumented) cover the read side: that an itemised entry surfaces its
  largest line-item category with a count of the rest, in **both** list
  surfaces. They are separate statements -- the Ledger list reads the per-book
  views, the bin reads `ledger_entry` because those views hide deleted rows --
  so each carries its own copy of the fallback and each is covered separately.
  Fixing the list and finding the bin still said "Unfiled" is how that lesson
  was learned.
- The bin's copy tripped `LedgerIsolationTest`'s aggregate rule, which was a
  string proxy rather than a test of Law 2's actual invariant. Sharpening it is
  recorded as an amendment under ADR-0002's Consequences, per that ADR's own
  instruction to come back rather than add an exemption.
