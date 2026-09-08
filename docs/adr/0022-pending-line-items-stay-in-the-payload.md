# ADR-0022 — `pending_line_item` is not a table; itemised candidates ride the existing payloads

- **Status:** Accepted
- **Date:** 2026-09-08
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. **Closes `SPEC.md` §16 Q7** and amends
  §6.1 and §13's P4 row.
- **Spec sections touched:** `SPEC.md` §5.3, §6.1, §13 (P4), §16 Q7

## Context

`SPEC.md` §6.1 has carried `pending_line_item(...)` as an elision since P2, with
§16 Q7 saying it is "still presumed to mirror `line_item` minus `entry_id` plus
`pending_id` — but 'presumably' is still not a schema". §13's P4 row commits to
it: "`pending_line_item` lands here — it is what lets *ingest* produce an
itemised candidate at all."

Q7 deferred the definition on sound reasoning: nothing in the P2 pipeline could
produce an itemised pending row, so defining the table then would have meant
guessing a shape against a pipeline that did not exist. P4 is when OCR — the
only producer — arrives, so the question comes due.

**Reading the code first turns out to change the answer.** Between the writing
of Q7 and now, schema v8 added `pending_transaction.review_draft_json`, and it
already carries itemised lines.

## What already exists

- **`pending_transaction.extracted_json`** — "the extracted fields, as a
  versioned typed payload". Encoded by `ExtractedTransactionJson` at `"v":1`
  over `ExtractedTransaction`, a `:core:domain` type on which everything except
  `direction` and `confidence` is nullable. It is JSON *because* "a candidate is
  partial by definition — every typed column would be nullable anyway, and the
  set of extraction targets grows with the ruleset rather than with the schema".

- **`pending_transaction.review_draft_json`** (schema v8) — the user's
  corrections, encoded by `ReviewEditsJson` over `ReviewEdits`, which **already
  has `lines: List<ReviewEditLine>`**, each carrying name, unit price (as typed
  text *and* as minor units), quantity (text *and* milli), category and
  subcategory. The review screen already reads and writes it, and it already
  survives process death.

- **`PendingTransaction.effective`** — the overlay that merges the two, with
  field-by-field fallback, guarded by `Bug15_EditsReachEverySurfaceTest`.

So the machinery Q7 anticipated needing a table for is, for the *edited* half,
already shipped and tested.

## Options considered

### Option A — Build `pending_line_item` as presumed

| | |
|---|---|
| Summary | A table mirroring `line_item`, keyed to `pending_id` |
| Cost | A migration (Law 4, `CLAUDE.md` §7 Danger Zone) with its `MigrationTest` and committed schema JSON; a `BackupPayload` list; a CSV writer; a DAO |
| Risk | Low individually. The cost is that none of it is optional: `ExportCoversEveryTableTest` asserts the CSV file set matches `schemas/{VERSION}.json` `entities` in **both** directions, so the table forces backup and export work in the same commit. |

The honest case for it: per-line OCR confidence and bounding-box provenance
would let the review screen highlight a line on the image. Both are equally
expressible in JSON, so this is an argument for the *data*, not for the table.

### Option B — Extend the two existing payloads

| | |
|---|---|
| Summary | `ExtractedTransaction` gains `lines: List<ExtractedLineItem>`; `ExtractedTransactionJson` goes to `"v":2`. Corrections continue to use `ReviewEdits.lines`, unchanged. |
| Cost | A payload version bump. **No migration, no new table, no CSV, no backup field.** |
| Risk | A large receipt puts ~40 line objects in one JSON column. `extracted_json` is already unbounded text and a receipt is a few KB. Nothing queries it. |

### Option C — Reuse `line_item` with a nullable `entry_id`

Rejected in one line: it would put uncommitted candidate rows in the table that
Law 1 reserves for approved ledger content, and every existing `line_item` query
would need a new predicate it currently cannot forget.

## Decision

**No `pending_line_item` table. OCR's extracted lines go into `extracted_json`
as a versioned `lines` array; user corrections continue to ride
`review_draft_json`; `ApproveTransactionUseCase` writes real `line_item` rows at
approval, as it already does for manual itemised entries (ADR-0018).**

Three arguments, in the order that decided it.

**The codebase's own stated reasoning points here.** `extracted_json` is JSON
because a candidate is partial by definition and its fields grow with the
ruleset. A receipt line is *more* partial than a bank SMS field, not less — OCR
routinely reads a name with no price, or a price with no quantity — and the set
of things a receipt line can carry grows with the OCR pipeline exactly as
extraction targets grow with the ruleset. A table of forty rows with every
column nullable is precisely the shape those two JSON columns exist to avoid.

**Nothing reads pending lines relationally.** `docs/DATAVIZ-PLAN.md` §5 is
explicit that item-grain analytics (Family B) reads an item-observation view
over `line_item` joined to `ledger_entry` — that is, *post-approval* data. There
is no query, present or planned, that a `pending_line_item` table would serve.
A table exists to be queried; this one would only ever be written and read back
whole, by one screen, which is what a payload column is for.

**The cost is not the table, it is everything a table drags with it.** A
migration is the single most dangerous class of change in this repository
(`CLAUDE.md` §7), and `ExportCoversEveryTableTest` makes the backup and CSV work
mandatory rather than optional. Spending that on a structure with no reader is
the trade this ADR declines.

**This narrows the P4 migration to `attachment` and `item_category_memory`**,
which is a material reduction in the phase's riskiest work.

Against it, and worth stating: §13's P4 row and §6.1 both promise this table in
writing, so this ADR contradicts the spec rather than merely filling a gap. That
is the reason it is an ADR and not a commit message. The spec is updated to
match.

## Consequences

**What this makes easy.** P4 ships one migration instead of one plus a table
nothing queries. An OCR line gains a new field — a confidence, a bounding box, a
unit-of-measure string — by editing a `:core:domain` data class and bumping a
payload version, with no schema change and no CI schema guard involvement.

**What this makes hard.** Anything that later wants to query across pending
lines — "show me every unreviewed candidate containing milk" — cannot, and would
have to either scan payloads or introduce the table after all. That is a real
limit and it is accepted because no such surface is specified or planned.
Payload migration also becomes this data's versioning story: a `"v":1` row must
keep decoding after `"v":2` ships, which `ExtractedTransactionJson` already has
to do and now has more to do it with.

**What we now have to maintain forever.** `ExtractedTransactionJson`'s version
ladder, and a decoder for every version ever written to a user's vault.

**What would make us revisit this.** A specified surface that queries across
pending line items. Measured evidence that decoding a large receipt's payload is
slow enough to matter on the review screen. Or item-grain analytics being asked
to include unapproved candidates — which would be a Law 1 discussion first.

## Verification

- **`ExtractedTransactionJsonVersionTest`** — a committed `"v":1` fixture must
  still decode after the `"v":2` bump, and a `"v":2` payload with lines must
  round-trip. The corpus rule applies: the `"v":1` fixture is never re-recorded.
- **`ExportCoversEveryTableTest`** — already the enforcement that this decision
  does not quietly acquire a table later without the backup and CSV work; it
  fails the moment `schemas/{VERSION}.json` gains an entity the CSV does not.
- **`Bug15_EditsReachEverySurfaceTest`** — extends to lines: an edited line must
  reach every surface that draws a candidate, the same property the scalar
  fields already have.
