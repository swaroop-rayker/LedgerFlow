# Statement fixtures (V2-4 Import)

Bank statement layouts for `StatementCorpusTest` (`:core:data`). Each file is
**synthetic**: the layout — preamble, headings, separators, quoting, date and
amount shapes, the summary block after the table — is copied from a real
export, and every value in it is made up.

| File | Layout |
|---|---|
| `split-with-preamble.csv` | Bank header lines, then `Date, Narration, …, Withdrawal Amt., Deposit Amt., Closing Balance`; `dd/MM/yy`; Indian grouping; a summary block after the table |
| `debit-credit-columns.csv` | Every cell quoted, CRLF, `Txn Date` beside `Value Date`, `Debit` and `Credit` with a space for empty, `2 Oct 2026` |
| `signed-with-drcr.tsv` | Tab-separated, one `Amount` column with a `Dr / Cr` column beside it, ISO dates |

**When a real statement imports wrongly**, it becomes a fixture here: keep its
layout exactly, replace every name, number, reference and amount, and add its
expected reading to the test. Never commit a real statement.
