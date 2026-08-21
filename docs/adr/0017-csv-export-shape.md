# ADR-0017 — CSV export is a faithful per-table dump, with money written twice

- **Status:** Accepted
- **Date:** 2026-08-21
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. Implements `SPEC.md` §5.9's CSV row.
- **Spec sections touched:** `SPEC.md` §5.9, §5.5; `CLAUDE.md` §2 Laws 2 and 3

## Context

`SPEC.md` §5.9 specifies the CSV export in one table row:

> | Data export | CSV (one file per table, zipped) | none (user's choice, warned) | Manual, SAF destination |

That fixes the container and the trigger and leaves three things open, two of
which run into the Seven Laws. They are settled here rather than in the code,
because each is the kind of decision that is invisible once written and
expensive to change after a user has built a spreadsheet on top of it.

**1. What a money column contains.** `amount_minor` is a `Long` in minor units —
25500 for ₹255.00. Law 3 bans `Float`/`Double` for money, so a decimal can only
be produced by integer arithmetic and string assembly. That makes every option
Law-3-clean; the question is what the file should *say*. A dump of
`amount_minor` alone is lossless and unambiguous, and looks broken to somebody
who opens it in a spreadsheet. A decimal alone is readable and makes the export
stop being a dump of the table.

**2. Whether `ledger_entry` is one file or two.** §5.5 promises the two books
"separate lists". ADR-0015 carved out the bin on a stated property — *a list
with no figure derived from more than one row* — and a CSV satisfies that
trivially, because a CSV derives no figures at all. So one mixed file is
defensible. The question is whether it is *right*.

**3. Whether soft-deleted rows are exported.** Binned entries and hidden
categories, merchants and payment methods are all still rows. The bin's own
erase dialog tells the user to **export first** if they might want something
back, which makes this less of a preference than it looks.

## Options considered

### Money

| Option | Cost |
|---|---|
| `amount_minor` only | Faithful and lossless. A user opening `ledger_entry_debit.csv` sees `25500` against a coffee and concludes the export is broken. Every downstream formula needs a `/100` the user has to know about. |
| Decimal only | Readable. The export stops being a table dump; a re-import path would have to parse decimals back to minor units, reintroducing precisely the rounding surface Law 3 exists to remove. |
| **Both** | One extra column per money field. Nothing is lost, nothing needs explaining, and the two can be cross-checked against each other. |

### The ledger split

| Option | Cost |
|---|---|
| One file, `ledger` column | Matches "one file per table" most literally, and ADR-0015 already established the property that would permit it. But it makes the export the **second** place the partition is relaxed, and "the export mixes them" becomes quotable at the next surface that finds the partition inconvenient. |
| **Two files** | Costs nothing: the read is already per-book (`allForLedger(ledger)`, iterated), so this is where the rows already arrive. Needs no appeal to ADR-0015 at all. |

### Soft-deleted rows

| Option | Cost |
|---|---|
| Exclude | Shows only what the app shows. Makes the export **lossy in a way nothing tells the user about** — and someone exporting immediately before an erase, which our own dialogs instruct, would lose exactly the rows they were trying to preserve. |
| **Include, with `deleted_at`** | The rows exist and the column says which are which. |
| Include, in a separate `deleted/` folder | Clearest to read; doubles the file count and invents structure §5.9 does not describe. |

## Decision

**The CSV export is a faithful per-table dump of the whole database, zipped,
with three shaping rules:**

1. **Every money column is written twice** — the schema's `_minor` integer
   verbatim, and a derived decimal string beside it. The decimal is built by
   integer division and remainder with zero-padding; **no floating point touches
   a money value on this path**, which `CsvMoneyTest` asserts against values
   chosen to be exactly the ones a `Double` would round wrong. This applies to
   `amount_minor`, `original_amount_minor`, `unit_price_minor` and `total_minor`.

2. **`ledger_entry` exports as two files**, `ledger_entry_debit.csv` and
   `ledger_entry_credit.csv`. This is not a concession to Law 2's letter — a CSV
   could not violate it — but to §5.5's promise, which is about what the user is
   handed. The rows already arrive per-book, so the split is free.

3. **Soft-deleted rows are included**, each file carrying its `deleted_at`
   column. The export and the `.lfbk` backup then describe the same database,
   which is the property that stops "what is in an export" from becoming a second
   list that rots independently.

**One further rule, which is the load-bearing one for maintenance: the export
reads `BackupPayload`.** `DatabaseBackupManager.export()` becomes `public` and
the CSV path consumes what it returns, rather than enumerating the tables again.
The alternative was seriously considered and rejected: two enumerations means
schema v6 adds a table to the backup, nobody thinks about the export, and the
new table is silently absent from every CSV a user takes. Nothing would fail. One
enumeration makes that impossible.

**Format:** RFC 4180. `CRLF` line endings, `,` separator, fields quoted only when
they contain a quote, comma, CR or LF, quotes escaped by doubling, UTF-8 with no
BOM. Timestamps are exported **twice as well** — the epoch-milli integer the
schema stores, and an ISO-8601 UTC string — for exactly the reason money is:
one is what re-imports, the other is what a human reads.

**No encryption, and the screen says so before it writes.** §5.9 says "none
(user's choice, warned)", and the warning is the whole of the user's protection
here: a `.lfbk` is phrase-derived and safe to put anywhere, and this file is the
opposite of that in every respect. It uses the same `LfDialogEmphasis.Warning`
treatment as the Recovery Kit and the bin's erase, which is the app's established
signal for "this one is on you".

## Consequences

**What this makes easy.** Opening the export in a spreadsheet and getting numbers
that look like money. Re-importing it later without a rounding story. Checking
that an export contains what the database contains, because both come from the
same enumeration.

**What this makes hard.** The files are wider than the schema — every money and
timestamp column appears twice. That is a deliberate trade of bytes for the two
audiences an export has, and it is the kind of thing that looks redundant right
up until somebody parses the wrong one.

**What we now have to maintain forever.** `BackupPayload` as the single table
enumeration. A new table added to it must gain a CSV file, and
`ExportCoversEveryTableTest` fails if the count of files in the zip stops
matching the count of lists in the payload — which is what turns "remember to
add it" into something a build can fail on.

**What would make us revisit this.** A request for a *readable* export — one
denormalised sheet with names instead of ids — is not a change to this ADR. It is
a second artifact, and §5.9 already has a slot for it: the XLSX export at P5,
with its "summary pivots". Widening the CSV toward readability would cost it the
faithfulness that is its entire purpose.

## Verification

- `CsvWriterTest` (`:core:data`, unit) covers RFC 4180: embedded commas, embedded
  quotes, embedded CRLF, leading/trailing whitespace, unicode, and the empty and
  null cases — null and empty string must survive as distinguishable, which
  unquoted-empty vs `""` does not achieve, so null is written empty and empty is
  written `""`.
- `CsvMoneyTest` asserts the minor→decimal conversion against values a `Double`
  round-trips incorrectly, and that negative and sub-unit amounts render with the
  right sign and padding.
- `ExportCoversEveryTableTest` asserts the zip's entry count matches
  `BackupPayload`'s list count, so a table added to one and not the other fails.
- `CsvExportRoundTripTest` (`:core:data`, instrumented) writes a real vault to a
  real zip and reads it back, asserting row counts per file and that an entry's
  `amount` column parses to its `amount_minor`.
