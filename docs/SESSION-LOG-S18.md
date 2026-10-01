# Session log — S18 (2026-09-29 → 10-01): P5 steps 1–3 — measured, exported, diagnosed

Starts after `b4c7194` (S17's log, 2026-09-29 16:32). Schema **v11, unchanged**
throughout. P5 steps 1 (benchmarks and baseline profile), 2 (XLSX export) and
3 (ingest diagnostics) are done; v2 and 120 Hz hardening were planned and not
started.

## 1. Step 1 — benchmarks and the first baseline profile (`c5200a6`)

A `benchmark` build type in `:app`: release code, R8 on, not debuggable,
installed beside the others as **`com.ledgerflow.bench`** ("LF Bench"),
smsFull only. Its `src/benchmark` source set seeds a throwaway vault under the
**public** BIP-39 test phrase and 2,005 synthetic entries over five years,
written through `ApproveTransactionUseCase` (Law 1). The owner chose both: the
seed hook exists in the benchmark build only, and smsFull only.

Measured on SM-S721B (SPEC §11 has the table): cold start 244 ms and warm
149 ms with the profile (264/169 without), against budgets of 700 and 250.
Memory after the Ledger scroll is 32.4 MB PSS; the RSS figure in the first
commit overstated it by counting shared pages.

What the harness needed, each found by measuring before believing:

- **UiAutomator 2.3.0's selectors see no Compose node in this app.** The raw
  accessibility tree has all of it, read with `UiAutomation.clearCache()`
  before every look; without that the walk read "Unlocking" for 30 s after
  the vault had opened.
- **The first fling in a fresh process compiles a Vulkan pipeline** (15–67 ms,
  once). The scroll benchmark now flings once, unmeasured, first.
- **Heat dominates.** A ten-minute suite at thermal status 2 gave back the
  unfixed code's numbers. Compare only cool, interleaved.

## 2. BUG38 — receipt OCR never worked in a release build (`68f38b6`)

Found in the benchmark build's startup log, not by any test. ML Kit starts
through Firebase component discovery, which builds each `ComponentRegistrar`
by its no-argument constructor; `firebase-components` keeps the class but not
the constructor under R8 full mode, so the first recognition threw. Debug is
not shrunk and every OCR test ran unshrunk code, so every release build since
P4 had no working OCR and nothing showed it. `app/proguard-rules.pro` keeps
`<init>()`; `Bug38_OcrWorksInAShrunkBuildTest` reads a drawn "TOTAL 245.00"
through the app's own recogniser in `com.ledgerflow.bench`, and was
mutation-checked on the device.

## 3. 120 Hz on the Ledger (`6ae169f`)

Every frame that missed 8.3 ms was composing and measuring a newly visible
row (~4 ms). Four changes: `compose-stability.conf` for the deeply immutable
core row types; one shared painter for the row's delete icon instead of a
rasterised vector per row; cached date formatters; and a prefetch window two
viewports ahead and one behind.

**Two conditions, both true.** In use (no tracing, the phone's own
accessibility service bound), the original code already met the budget: 4 late
frames of 1,401, then 3 of 1,409. Under Macrobenchmark, whose accessibility
connection is a TalkBack user's condition, late frames fell 33 → 12–14 per five
iterations and frame CPU P99 12.7 → 8.9 ms — **still not met there**.
`docs/PERF-120HZ-PLAN.md` is the plan for that condition, proposed and not
started; whether it is the bar is open question 4 below.

## 4. Plans written, not started (`6ff24ab`, `02997df`, `88c65e4`)

- **`docs/V2-PLAN.md`**: the owner's v2 brief as phases V2-0..V2-8, after P5.
  It leads with eight decisions the brief collides with (D1–D8). Diagnosed
  from the code: tab switches use Navigation Compose's default 700 ms
  cross-fade (BUG-A); allowlisted non-transaction messages become empty
  candidates because no classifier exists (BUG-B); and **Home was never built
  past its banners** (BUG-C) — "Nothing here yet" is drawn whatever the vault
  holds.
- **Home v1**, decided by the owner on 2026-09-30: capture coverage gets a Home
  card, and recent income is a separate card from recent spending (never
  interleaved — Law 2).
- **`docs/PERF-120HZ-PLAN.md`**: measurement tooling first, then per-row cost.

## 5. Step 2 — the XLSX export (`f902170`, `397e56e`, ADR-0004)

- **BUG39 first.** Reading the CSV tables to reuse them, every `budget.csv` row
  turned out to carry 15 cells under 13 headers since `ba441c0`.
  `ExportCoversEveryTableTest` exports an empty payload, where no row can be
  the wrong width. `Bug39_EveryCsvRowIsAsWideAsItsHeaderTest` fills one row
  per table from `BackupPayload`'s descriptors, so new columns are covered
  without editing it.
- **The writer is `org.dhatim:fastexcel` 0.20.2**, the owner's choice over a
  hand-rolled one; Apache POI stays banned. Monthly totals (Spent and Received
  side by side, no net), Spending by month and Income by month (one book each),
  then every table as a sheet. Money cells are `BigDecimal.valueOf(minor,
  exponent)`, so no `Double` touches money in the app (Law 3).
- **Checked in Excel on 2026-10-01**, driven through Excel's own engine on the
  dev box: it opened without repair, money cells are numbers, and every pivot
  total equals Excel's own `SUM`. Monthly totals agree with both pivots
  (Received 54,00,000.00 = the seeded 90,000 × 60). The check script first
  reported Monthly totals at twice the pivots — it had summed the Total row
  along with the months. Re-read before it was believed. Google Sheets was not
  checked. The scratch export on the phone was deleted afterwards.

## 6. Step 3 — ingest diagnostics (`021272b`, 2026-10-01)

Design brought to the owner first (CLAUDE.md §10) and approved as recommended:
a visible "Diagnostics" row last on More; 30 days by default, then 90 days and
all time; confidence thresholds 0.5 and 0.8; SPEC §11's JankStats line dropped
(the benchmarks do that job); BUG7(c)'s crash records kept as their own item,
not folded in. SPEC §5.6 "Ingest diagnostics, as shipped" records it.

- **Counts and durations, never money and never message text.**
  `DiagnosticsCarryNoMoneyTest` walks every field of the report by reflection;
  a deliberately added `repairedAmountMinor` turned it red with exactly that
  name. Personal SMS appear as a count, never a sender.
- **Nine SQL aggregates** (`IngestDiagnosticsDao`), medians by nearest rank
  (`ORDER BY … LIMIT 1 OFFSET n`), no schema change. The duplicate partition
  `LEFT JOIN`s through to the winner, so a duplicate whose candidate was erased
  is still counted. Making that join an inner join turned exactly the partition
  test red on the device, and nothing else.
- **"Show in Inbox"** opens the Suppressed filter: `Destination.Inbox` gained an
  optional `filter`, read by `InboxViewModel.FILTER_ARG` and held to the route
  by `InboxFilterArgumentTest`.
- **First feature screen with goldens**: Roborazzi in `:feature:settings` (a third
  copy of `LfScreenshotOptions`), six goldens at 1x and 2x, every one reviewed.
  The first 2x recording was cut off half way down; the windows are now sized
  per scale.
- **The owner's real data found a gap on day one.** 52 messages, confidence
  bars adding up to 42. The difference is candidates erased from the Inbox: the
  raw record survives, the confidence does not. The card now says so.
- **What it showed:** the payment-app notifications are the weak spot (Google
  Pay 6 of 6 read weakly, SBI YONO 3 of 3) where the bank SMS mostly parse —
  evidence for v2's BUG-B, found without reading a message body. Capture to
  Inbox is about 0.3 s on both sources.
- The More row's Export subtitle said "CSV" a day after Excel became the
  default; fixed with it.

## 7. What the discipline caught, and what it got wrong

- **A stale results file read as a pass** (09-30): "9 tests" for a 12-test file
  came from the previous run's XML. Results are now read from a fresh
  directory, with the timestamp checked.
- **Python text mode writes CRLF on Windows.** A scripted edit sweep turned 17
  LF files to CRLF; git only warned. Converted back the same day; memory note
  updated.
- **Detekt's function counter** tripped on the database class's twentieth DAO
  accessor. Suppressed with its reason, as `CsvTables` already is, rather than
  splitting a registry.
- **"Two identical LedgerFlow icons"** (the false missing-feature report of
  09-27) was settled by `19528a2`'s "LF PlaySafe" label; the memory note said
  the labels were undecided and was corrected.

## 8. State

| | |
|---|---|
| Gate | Step 3: `preMergeCheck --no-build-cache`, 3,202 tasks; re-run incrementally after the last screen change. Earlier commits as stated in their messages |
| Guards | schema, versionCode, corpus-order, workflows: pass |
| Pushed | everything through `397e56e` |
| Committed, not pushed | P5 step 3 (`021272b`) and this log, with SPEC §5.9's Excel result; push is the owner's call |
| Device | real app `com.ledgerflow.debug` from step 3's tree, installed over the top, vault intact (`firstInstallTime` 2026-08-25 unchanged; opens on Home). LF PlaySafe: throwaway vault. LF Bench: 2,005 synthetic entries, public test phrase |

## 9. Outstanding

1. **Owner decisions:** V2-PLAN D1–D8; merchant categories vs a merchant's
   default spending category; the ghost Inbox entries (the Diagnostics screen
   now names the senders); whether the harsh 120 Hz condition is the bar, and a
   second, cheaper test phone.
2. **P5 next:** step 4 (accessibility pass and goldens for every screen without
   them — the point to lift `LfScreenshotOptions` into `:core:testing`), step 5
   (`targetSdk` 37), step 6 (Play listing, playSafe track; the owner does the
   console side).
3. **CI's corpus blocker** (private corpus repo and token, owner-only), so
   `instrumented` and `assemble` still never run on GitHub. `ubuntu-latest`
   moves to Ubuntu 26 on 2026-10-19.
4. **Owner TESTING rows:** D5 (open the kit PDF, scan its QR), D9b (TalkBack
   reads the phrase chips; recordings blank phrase screens), D11 (from
   2026-09-30, about eight days).
5. Carried: `CsvWriter` writes two decimal places whatever the base currency
   (ADR-0004 notes it; v2); BUG7(c)'s crash records; Google Sheets not checked
   against the XLSX; and S17's carried items (phrase rotation and the sealing
   key, the Recovery screen's restore entry, Q22 transfers, `SafBackupFolder`
   untested).
