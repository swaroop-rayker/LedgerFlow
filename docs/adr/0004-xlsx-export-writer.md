# ADR-0004 — The XLSX export is written with fastexcel, raw tables plus per-book pivots

- **Status:** Accepted
- **Date:** 2026-09-30
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. Implements `SPEC.md` §5.9's XLSX row.
- **Spec sections touched:** `SPEC.md` §5.9; `CLAUDE.md` §2 Laws 2 and 3, §9

## Context

`SPEC.md` §5.9 asks for an XLSX export — "multi-sheet: entries, line items,
categories, merchants, budgets, summary pivots" — and its library note leaves
the writer open: Apache POI is unusable on Android (dex size, xmlbeans), so
either `org.dhatim:fastexcel` or a hand-rolled SpreadsheetML zip. "Summary
pivots" was never defined. The CSV export (ADR-0017) already settled the raw
tables' shape: one document per payload table, `ledger_entry` split per book,
money and timestamps written twice.

## Decision

### 1. Writer: `org.dhatim:fastexcel` 0.20.2

Owner's choice (2026-09-30) over the hand-rolled writer that was also
recommended. Writer-only, Apache-2.0, ~130 KB, one runtime dependency
(`com.github.rzymek:opczip`, its streaming zip). It streams rows to the output,
so a large ledger never sits in memory as a workbook model. It is used in
`:core:data` only, behind `ExportRepository`.

What it buys over hand-rolling: files that Excel, LibreOffice and Google Sheets
are known to open, without this repository owning the SpreadsheetML details
(shared strings, styles, content types) that make a hand-written file fail
strict readers. What it costs: a dependency to keep current, and Norton's TLS
interception to get past once when it is first resolved.

### 2. Sheets

1. **Monthly totals** — one row per month: `Month`, `Spent`, `Received`.
   Two figures side by side and **no net, no balance, no difference column**
   (Law 2). No row or cell combines the books.
2. **Spending by month** — categories × months, debit book only, plus a
   per-category total column and a per-month total row. Each total sums
   within one book.
3. **Income by month** — the same for the credit book, on its own sheet.
4. **The raw tables** — one sheet per CSV document, same columns in the same
   order, `ledger_entry_debit` and `ledger_entry_credit` separate.

Pivots read **`daily_rollup`** through its existing ledger-bound query
(`allFor(ledger)`), so their figures are exactly what Analytics shows —
including ADR-0018's attribution of itemised bills to their line items'
categories — and soft-deleted entries are excluded the way Analytics excludes
them. Grouping is by calendar month of `local_date`, in `Long` minor units.

### 3. Cells

- **Money is a number in the spreadsheet, and never a `Float`/`Double` in the
  app.** Pivot cells and the raw sheets' decimal money columns are written as
  `BigDecimal.valueOf(minor, exponent)` — exact, assembled from the `Long` —
  with a `#,##0.00`-style format for the currency's exponent. The spreadsheet
  application stores what it parses; nothing in LedgerFlow does floating-point
  arithmetic on money (Law 3).
- **The exponent comes from `CurrencyExponent`** (the ISO-4217 table in
  `:core:model`) for the vault's base currency, so a JPY or BHD book is not
  shown with two decimals. The raw sheets keep ADR-0017's CSV rendering for
  their `_minor` integers (written as whole numbers) and parse the CSV decimal
  twin as the number beside it.
- Identifiers, ISO timestamps and everything else are text, exactly as in the
  CSV, so no id or date is reinterpreted by a spreadsheet's type guessing.

### 4. Same guarantees as the CSV export

Streamed straight into the SAF document ("wt", truncating), no temp file in
`filesDir`; the same unencrypted-file warning before every export; runs on the
IO dispatcher; typed `ExportResult`.

## Consequences

- `ExportCoversEveryTableTest` and `Bug39_EveryCsvRowIsAsWideAsItsHeaderTest`
  guard the raw sheets automatically, because they are built from the same
  `CsvTables` documents.
- The dependency is pinned in `libs.versions.toml` and merges no permission
  (`EXPECTED_MERGED_PERMISSIONS` would fail otherwise).
- The CSV writer still renders every money decimal with two places whatever the
  base currency — a pre-existing limitation of ADR-0017 for zero- and
  three-decimal currencies, recorded for v2 (general users) rather than changed
  here.
