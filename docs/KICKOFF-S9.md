# KICKOFF S9 — The Ledger list

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the scope for S9 only.

---

## 1. The bug this closes

The user reported four bugs during a product review on 2026-08-19. Three were
fixed. This is the fourth, and it is the last one outstanding:

> **Even after saving a successful expense/income entry it does not show up in
> the Ledger section.**

That report is accurate, and the cause is not a bug in the save path — the entry
*is* committed. `feature/ledger/.../LedgerScreen.kt` is a **stateless hardcoded
empty state**. It has no ViewModel, no repository, and never reads the database.
Its own copy currently promises the opposite:

> "Expenses and income are kept as two separate books. Add an entry with the
> button below and it appears in whichever one you chose."

So the screen tells the user entries will appear there, and then cannot show
them. Verify the save path is genuinely fine before building on that assumption
(it was verified working on device in S8 — an entry saves, and
`LedgerRepositoryInstrumentedTest` covers the write), but do not take this
paragraph's word for it.

**Law 7 applies:** this ends with a named regression test. Suggested name —
`Bug10_SavedEntryAppearsInLedgerTest`.

---

## 2. What already exists (verified, 2026-08-19)

| Piece | State |
|---|---|
| `ApproveTransactionUseCase` + `DefaultLedgerRepository.approve` | Done, tested, the only writer (`LedgerSingleWriterTest` enforces it) |
| `debit_entries` / `credit_entries` views | Exist, are full projections of `ledger_entry` |
| `LedgerEntryDao.observeDebits()` / `observeCredits()` | Exist, return `Flow<List<...View>>`, ordered `local_date DESC, occurred_at DESC` |
| `LedgerRepository` (domain) | Has `approve`, `observeRecentCombos`, `baseCurrency` — **no list read** |
| `feature/ledger` | One file, `LedgerScreen.kt`, empty state only. `build.gradle.kts` has no dependencies block at all |
| Paging 3 (`paging = "3.5.1"`, `androidx-room-paging`, `androidx-paging-compose`) | In `libs.versions.toml`, **not used by any module yet**. This would be its first use |
| `MoneyFormat` (`:core:designsystem`) | Done — `plain` / `symbolised` / `spoken` / `parse` |

---

## 3. Scope

Build the Ledger tab so it reads the two books and shows what is in them.

1. **`:core:database`** — `PagingSource` queries over the two views. **No schema
   change**, therefore no migration and no new schema JSON. If you find yourself
   writing a `Migration`, stop and re-read: this is a read-path change only.
2. **`:core:domain`** — a list-read API on `LedgerRepository`, taking a
   `ledger: LedgerType`. **No overload omits the parameter** and there is no
   variant returning both books (Law 2, ADR-0002).
3. **`:core:data`** — the implementation, mapping view rows to `:core:model`.
4. **`:feature:ledger`** — `LedgerViewModel` + `LedgerUiState` + `LedgerEvent`,
   an `Expenses | Income` segmented control, and the list. The module needs its
   dependencies block written from scratch.

**Out of scope for S9** (do not drift into these): entry detail, edit, delete,
search, filters, the date-range bar, and the Dashboard. Get the list correct and
stop.

---

## 4. Decisions to make — do not invent silently

**a. Does Paging reach `:core:domain`?** CLAUDE.md §8 is unambiguous — "Never
load a full ledger into memory. Paging 3, always." But CLAUDE.md §3 says
`:core:domain` depends on `:core:model` + `:core:common` only. `PagingData` is
an androidx type, and putting it on a domain interface pulls androidx into the
layer that is deliberately kept clean.

Three honest options: put `PagingData` on the domain interface anyway (pragmatic,
what most Now-in-Android-shaped codebases do, and `paging-common` is not an
Android-runtime dep); keep domain paging-free and let `:feature:ledger` talk to a
paging-specific interface; or wrap it. **This is an ADR-shaped decision — ask the
owner before choosing, and write `docs/adr/0014-*.md` for whatever is chosen.**

**b. Section headers by date.** SPEC §9.3 does not state whether the list is
grouped by day. If you want them, they are a stickyHeader over the pre-sorted
query, not a client-side regroup of a full list — but confirm before building.

---

## 5. Non-negotiables for this specific work

- **Law 2 above all.** Two separate flows, two separate queries, two separate
  totals. Never one list with a sign column. Never a combined figure anywhere on
  the screen. The tab is a *partition selector*, not a filter over shared data.
  `LedgerIsolationTest` will fail the build if a query names `ledger_entry`
  without a `ledger` parameter.
- **Reads go through the views**, never `ledger_entry` directly (ADR-0002).
- **Money is `Long` minor units** and formats through `MoneyFormat`. No `Double`
  anywhere near an amount.
- `LazyColumn` items carry `key` **and** `contentType`.
- **The visual design philosophy in CLAUDE.md §5 applies.** This is a list
  screen — it is exactly where "compact by default" and "a list of three items
  filling the screen means the item is too big" bite. Ledger rows should be
  denser than the Organise cards, not looser.
- Empty state stays for a genuinely empty book, but its copy must stop promising
  something the screen cannot do.

---

## 6. Definition of done

CLAUDE.md §12 in full, plus specifically:

- [ ] `Bug10_SavedEntryAppearsInLedgerTest` — approve an entry, assert it appears
      in its own book **and is absent from the other one**. The second assertion
      is the one that matters; the first passes even if Law 2 is broken.
- [ ] `.\gradlew preMergeCheck` green on **both** flavours
- [ ] Verified on the physical device: save an expense, see it; switch to Income,
      it is not there; force-stop and relaunch, it is still there
- [ ] Font scale 2.0 and RTL without truncation or overlap
- [ ] `SPEC.md` updated if behaviour diverged
- [ ] ADR written for the decision in §4a

---

## 7. Environment notes that cost time last session

- Gradle needs `JAVA_HOME`. On this machine:
  `$env:JAVA_HOME = "D:\Software\Android App development\jbr"`. It is not set in
  the shell by default and `bash` cannot see it at all.
- `adb` is not on PATH:
  `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- **Never `adb uninstall`.** Use `installSmsFullDebug`. Note also that
  `connectedAndroidTest` uninstalls the APKs and takes the user's real vault with
  it — `gradle.properties` disables that; do not remove the line (CLAUDE.md §4).
- The device font scale is the user's, currently **1.15**. If you change it for a
  check, **change it back**.
- Bash heredocs mangle escapes and have broken mid-script here. For multi-line
  edits, write a Python script to the scratchpad and run it with PowerShell.
- The device screen is 1080x2340 at density 510 (3.1875 px/dp). `uiautomator
  dump` plus the nav bar's `mFrame` from `dumpsys window windows` is how layout
  claims get checked — the nav bar inset is 153px and is *not* reclaimable slack.

---

## 8. Session log — what shipped just before this

`cd334c4` design philosophy written into CLAUDE.md §5 ·
`e47061f` one card shape across Organise ·
`1ccf91a` Add bar to its floor, header tightened ·
`6115f87` Add bar's doubled padding returned to the list ·
`bd71c25` delete confirmations ·
`5072fc8` SPEC/CLAUDE reconciled with what shipped

Known and deliberately unfixed: at font scale 2.0 the Organise header (title +
two stacked segmented controls) leaves the list a sliver. Pre-existing, flagged,
out of scope — but it is the same class of problem this list screen must avoid.
