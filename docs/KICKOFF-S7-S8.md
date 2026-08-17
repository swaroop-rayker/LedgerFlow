# LedgerFlow — S7 + S8 Resume Prompt

> **How to use:** open Claude Code in the repo root and paste everything below the line as your first message.

---

## ROLE & AUTHORITY

You are the lead engineer on **LedgerFlow**, an Android-only, offline-first, encrypted expense tracker. Two documents govern you:

- **`SPEC.md`** (repo root) — what to build. Authoritative on requirements, schema, security model, phasing.
- **`CLAUDE.md`** (repo root) — how to build. Authoritative on conventions, module boundaries, the Seven Laws, danger zones.

**Read both in full before writing a line**, plus `docs/adr/0002` (ledger partitioning), `0009` (key rotation), `0010` (crypto libraries) and `0011` (KEK-C dropped). Where they conflict with anything I say casually in chat, they win — tell me and I'll amend the doc rather than you silently diverging. Where they're silent or ambiguous, **ask**. Do not infer.

`SPEC.md §2.5` (Decision Log, D-01…D-08) and `docs/adr/` are settled. Do not relitigate them.

---

## WHERE THE PROJECT STANDS

**Phase 0 complete. P1 steps S0–S6 complete**, on `main`, verified on the physical device. `HEAD` is `3bb108d`; `main` is **10 commits ahead of `origin/main`** (nothing has been pushed this phase — that's fine, don't push unless I ask).

Recent commits, newest first:

```
3bb108d style(designsystem): widen row actions, stack segments at large font scales
2285712 style(designsystem): outlined row actions, card dividers, centred wrap
4c90dc2 feat(categories): category, merchant and payment-method management
e3519bf db: schema v2 -- draft_entry, merchant_alias, category groups
99f51ea feat(shell,taxonomy): add the nav shell and the category/merchant layer
d3380b6 feat(vault): wire the §7.3 unlock flow and the onboarding SAF pickers
0290fe3 chore(build): add the application convention plugin and bootstrap Hilt
53b82c7 docs: close the four P1 gating questions
```

### The 13-step P1 plan, and where we are

| # | Step | Status |
|---|---|---|
| S0 | `ledgerflow.android.application` convention plugin + Hilt bootstrap | ✅ |
| S1 | Unlock flow (§7.3): Keystore → canary → DB, Recovery screen, KEK-A re-heal | ✅ |
| S2 | SAF: Recovery Kit (txt + PDF) + backup tree grant | ✅ |
| S3 | App shell: type-safe Navigation Compose, bottom bar + centre action | ✅ |
| S4 | `:core:domain` + `:core:data` taxonomy layer + seed set | ✅ |
| S5 | **Schema v2** (`draft_entry`, `merchant_alias`, `category_group`, `category_group_member`) | ✅ |
| S6 | `:feature:categories` — category/merchant/payment-method management | ✅ |
| **S7** | **`:core:domain` ledger layer — `ApproveTransactionUseCase`, draft repository** | ⬅ **do this** |
| **S8** | **`:feature:entry` — manual entry, draft persistence, BUG6 test** | ⬅ **do this** |
| S9 | `:feature:ledger` — Paging 3, filters, search | pending |
| S10 | `:feature:export` — CSV via SAF | pending |
| S11 | `:feature:ingest` — `TransactionIngestSource` abstraction + flavour skeleton only | pending |
| S12 | `TESTING.md` + carryover (bundled font, onboarding CTA pinning) | pending |

### Current verified state

- `preMergeCheck` green on **both** flavours.
- **312 unit tests, 0 failures.**
- **62 instrumented tests, 0 failures** on SM-S721B (`core/database` 19, `core/data` 35, `core/designsystem` 4, `core/crypto` 5).
- `guard-schema.sh` and `guard-version.sh` pass.
- Schema **v2** committed (`core/database/schemas/.../2.json`), `MIGRATION_1_2`, `MigrationV1ToV2Test`, backup round-trip extended to all four new tables.

---

## SCOPE OF THIS SESSION: **S7 + S8 ONLY**

Complete both fully, then stop. Do not start S9.

### S7 — `:core:domain` / `:core:data` ledger layer

- **`ApproveTransactionUseCase` is the only thing that may insert into `ledger_entry`** (Law 1). It is a single Room transaction: entry + line items together.
- It must enforce the §6.1.1 invariant that `subcategory_id`'s parent equals `category_id` — a SQLite `CHECK` cannot contain a subquery, so this is the enforcement point. Add `LedgerEntryConsistencyTest`.
- **Draft repository** over `draft_entry` (D-06, `SPEC.md` §6.1.2): one row per in-flight entry, slot = `UNIQUE(ledger, editing_entry_key)`, versioned JSON payload, 30-day purge of orphans on app open.
- Manual entry **does not** route through `pending_transaction` — settled in §5.4. It calls `ApproveTransactionUseCase` directly with `source = MANUAL`, `source_ref_id = NULL`. `pending_transaction` lands at P2.

### S8 — `:feature:entry`

- Manual entry, **both ledgers** (segmented control), multi-line-item editor.
- **≤4 taps for a repeat expense** (§5.4). Recent/frequent combos as chips.
- **Draft persists to Room on every field change, 300 ms debounce** (BUG6). Ships with a named regression test — `Bug6_DraftSurvivesProcessDeathTest` — that fills the form, kills the process, relaunches and asserts the draft restored field-for-field.
- Replace `EntryPlaceholder` in `app/src/main/java/com/ledgerflow/navigation/Placeholders.kt` and wire the real screen into `LedgerFlowShell`.
- Money is `Long` minor units end to end. No `Float`/`Double` anywhere near an amount (Law 3).

---

## ENVIRONMENT — READ BEFORE RUNNING COMMANDS

| Fact | Implication |
|---|---|
| **Windows 11**, PowerShell | `.\gradlew`, not `./gradlew`. |
| **No `java` on PATH** | `$env:JAVA_HOME = "D:\Software\Android App development\jbr"` before every Gradle invocation. |
| **adb** | `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe` — not on PATH. |
| **Git Bash** | `D:\Software\Git\bin\bash.exe`. Bare `bash` is WSL and will fail the guard scripts. |
| **Device** | Samsung SM-S721B, Android 16 / API 36. Confirm with `adb devices -l` before any `connected*` task. |
| **Device font scale is 1.15**, not 1.0 | Layout maths must account for it. If you change it for testing, **restore it to 1.15**. |
| **Never `adb uninstall`** | Use `installSmsFullDebug`. |

The existing debug install predates the taxonomy seed, so its category list starts empty — that is expected, not a bug. Seeding runs only at vault creation.

---

## HARD-WON FACTS FROM THE LAST SESSION — do not rediscover these

**Tests**
- An instrumented `@Test fun x() = runBlocking { … }` whose last expression is non-`Unit` (a Truth `containsExactly`, a `.success()` returning a value) makes JUnit reject **the whole class** with "should be void". Use `runBlocking<Unit>`.
- **Close every SQLCipher database you open in a test's `tearDown`.** Leaving ~30 open connection pools crashes the instrumentation process with a bare "Process crashed" and an empty `<failure>` element that looks exactly like flake.
- `TextLayoutResult.hasVisualOverflow` is **unreliable when `softWrap = false`** — it tracks the incoming constraint, not what was painted, and reports `true` for labels that render fine. Assert `lineCount` and `getLineEnd(0, visibleEnd = true)` instead.
- ViewModels using `stateIn(WhileSubscribed)` emit nothing until collected. Tests must start a collector and `advanceUntilIdle()` before asserting.

**Guards and static analysis**
- `LedgerIsolationTest` now scans `UPDATE`/`DELETE` as well as `FROM`, and splices concatenated string literals before matching. **Any new write to `ledger_entry` must bind `:ledger`**; if an operation spans both books, iterate `LedgerType.entries` rather than dropping the predicate.
- detekt limits that will bite: `LongMethod` 60 lines, `CyclomaticComplexMethod` 15. Route a large `onEvent` `when` into per-concern sub-handlers, keeping the **outer** `when` exhaustive over the sealed type (no `else`); the sub-handlers take `else -> Unit`.
- `MatchingDeclarationName`: if a file's *first* top-level declaration is a class whose name ≠ the filename, it fails. Put enums/data classes **after** the composables in `Lf*.kt` files.
- `bannedApiCheck` enforces the `!!` and `cacheDir` bans by regex.

**Runtime**
- StrictMode is armed in debug with a `penaltyListener` that re-throws **only** violations whose stack contains `com.ledgerflow` frames. Any main-thread disk read in our code kills the app immediately. `Dispatchers.Main.immediate` is what `viewModelScope` uses — hop to the injected `@IoDispatcher` for anything touching disk, and remember loading a resource (e.g. the BIP-39 wordlist) counts.
- `hiltViewModel()` comes from `androidx.hilt.lifecycle.viewmodel.compose`, not `androidx.hilt.navigation.compose` (deprecated).
- Repositories read the database through `VaultSession.whenUnlocked(): Flow<LedgerFlowDatabase?>` — the DB does not exist when Hilt builds the graph. Follow that pattern; do not capture a DAO at construction.
- `Clock` (`:core:common`) and `Uuid7Generator` are injected and provided by `CoreCommonModule`. Do not call `System.currentTimeMillis()` inline.
- Room's suspend DAO calls cannot run inside `runInTransaction`; use `RoomDatabase.withTransaction` from `room-ktx`.

**Driving the device with adb**
- `adb shell input text` drops characters at speed — type word-at-a-time with pauses when entering long strings.
- `input keyevent 111` (ESC) **dismisses a dialog**, not just the IME. Use `keyevent 4` (BACK) to close the keyboard.
- `uiautomator dump` escapes `&` as `&amp;`; match accordingly.

---

## UI DISCIPLINE — I raised this and it is now a standing requirement

**BUG9 is in `SPEC.md` §8**: a control's label must never wrap or break mid-word. The rules, already enforced in `:core:designsystem`:

- `LfButton` renders its label `maxLines = 1, softWrap = false`, no ellipsis.
- Two or more actions go in an **`LfActionRow`** (a `FlowRow`) so whole controls wrap, never words. A bare `Row` of three actions in a card is the defect.
- `LfSegmentedControl` stacks vertically when a segment would be too narrow for its label.
- `LfButtonStyle.Outlined` is the row-action style (border + faint fill + 112dp minimum width); `LfDivider` separates card content from its actions.

**When you change anything visual: screenshot it on the device and look at it.** Not once at the end — at each change. Check the normal case *and* font scale 2.0 (§9.6 requires 2.0× without truncation or overlap). Two real defects in the last session were found only by looking at pixels, and one of my "fixes" broke the normal case while fixing the large-font case. Measure rendered bounds rather than reasoning about dp when a layout decision is close.

---

## RULES OF ENGAGEMENT

- **Commit incrementally**, Conventional Commits, one logical change per commit. `db:` prefix for anything schema-touching. Commit directly on `main` (that is this project's established practice).
- **Tests alongside code**, not after.
- **Schema changes ship `Migration` + `MigrationTest` + regenerated schema JSON in the same commit.** If S7/S8 need a schema change, that is a **v3** — copy the DDL verbatim out of Room's generated `3.json`, never hand-write an approximation.
- **Propose, never unilaterally adopt, a new dependency.**
- **If a request would violate one of the Seven Laws, say so loudly.**
- **Flag over-engineering, including mine.**
- **Run the verification commands and report actual output. Never claim green without showing it.** Prefer fixing the code over loosening a guard; every detekt config change in this repo carries a written justification.
- When something fails once and passes on retry, **find the cause** rather than retrying — that rule already caught a real resource leak.

Verification commands:
```powershell
$env:JAVA_HOME = "D:\Software\Android App development\jbr"
.\gradlew preMergeCheck
.\gradlew :core:data:connectedSmsFullDebugAndroidTest
.\gradlew :core:database:connectedSmsFullDebugAndroidTest
& "D:\Software\Git\bin\bash.exe" scripts/guard-schema.sh
```

---

## OPEN QUESTIONS — do not invent answers

Already recorded in `SPEC.md` §16 and **not** blocking S7/S8:

- **Q7** — `pending_line_item` is still elided as `(...)`. Needed before P2/P4, not now.
- **Q11** — §7.3 step 3 (restore from `.lfbk`, type-DELETE "start fresh") has no entry point at first run. A user reinstalling after a factory reset is handed a *new* phrase with no "I already have one" branch. `DatabaseBackupManager` can already do the restore; it is a missing route, not missing machinery. Needed before first release.

If S7 or S8 turns up a genuine spec gap, add it to §16 rather than deciding it yourself.

---

## FIRST RESPONSE

Do exactly this, nothing more:

1. Confirm you've read `SPEC.md`, `CLAUDE.md` and ADRs 0002/0009/0010/0011.
2. Report the current build state: run `preMergeCheck` and `adb devices -l`, and show the actual output.
3. Give me your S7 + S8 breakdown — ordered, with a stopping point after each — and say explicitly whether you believe either needs a schema v3 and why.
4. Stop.
