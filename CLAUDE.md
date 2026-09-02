# CLAUDE.md — LedgerFlow

Operating manual for AI coding agents working in this repository. Read this fully before your first edit. `SPEC.md` is the source of truth for *what* to build; this file is the source of truth for *how*.

---

## 0. Locked Decisions

These are settled. Do not relitigate them, do not "helpfully" suggest alternatives mid-task. Changing any of them requires a superseding ADR.

| | Decision |
|---|---|
| **Stack** | Native Kotlin + Jetpack Compose. Not Flutter. Not XML Views. |
| **Currency** | One base currency per install (default INR), chosen at onboarding. `amount_minor` is **always** base currency. Foreign spend stores `original_amount_minor` + `original_currency` + `fx_rate_micro`, all user-entered. **No FX lookup, no conversion engine, no `INTERNET`.** |
| **Recovery** | 24-word BIP-39 phrase is mandatory and primary. It wraps the DEK *and* encrypts `.lfbk` backups. The optional passphrase wrap (KEK-C) is **dropped** — ADR-0011. Two factors: Keystore and phrase. Do not reintroduce a user-chosen secret anywhere on the key path; it would silently downgrade the scheme to whatever the user typed. |
| **Ingest** | SMS **and** notification listening are co-equal first-class sources, both shipped in P2, sharing one rule engine and one dedupe layer. Notification ingest is in **both** product flavours; SMS is `smsFull` only. |

Everything downstream of a capture adapter is **source-agnostic**. If you find yourself writing `if (source == SMS)` outside `:feature:ingest`'s adapter package, you've broken the abstraction.

---

## 1. What LedgerFlow Is

An Android-only, offline-first, encrypted personal expense tracker. Four ingest sources — **live SMS**, **bank/UPI notifications**, **receipt OCR**, **manual entry** — all of which land in an approval Inbox. **Nothing reaches the ledger without an explicit user tap.** Debits and credits are two isolated ledgers that never interact.

Dev environment: **Windows 11 + Android Studio**, testing on a physical device (Android 16 / API 36, forward-compatible to 17 / API 37).

---

## 2. The Seven Laws (violating any of these is a build failure, not a code-review comment)

1. **No auto-commit.** Only `ApproveTransactionUseCase` may insert into `ledger_entry`. Parsers, workers, and receivers write to `pending_transaction` only.
2. **Ledgers never mix.** Every query touching `ledger_entry` must filter on `ledger`. No SUM, JOIN, or UI element may combine DEBIT and CREDIT into one figure.
3. **Money is `Long` minor units.** No `Float` or `Double` for a monetary amount, and no currency arithmetic outside `Money`. Ever. The ban is on *money*, not on floating point generally — parser `confidence`, Jaro-Winkler scores and σ/μ ratios are legitimately real-valued, and the CI check is correspondingly scoped to money-shaped identifiers in `core/model` (`amount`, `price`, `total`, `balance`).
4. **No destructive migrations.** `fallbackToDestructiveMigration()` is banned. Every schema change ships an explicit `Migration` + a `MigrationTest` + an updated committed schema JSON.
5. **Persistent data lives in `filesDir`/`databases/`.** Never `cacheDir`, never external storage. `cacheDir` is for decoded-image scratch only.
6. **No `INTERNET` permission in release.** All parsing, OCR, and analytics are on-device. If you think you need the network, you're wrong — raise it as an ADR instead.
7. **Every bug fixed gets a named regression test.** Name it after the bug (`Bug6_DraftSurvivesProcessDeathTest`). The suite only grows.

---

## 3. Repository Layout

```
LedgerFlow/
├─ SPEC.md                        ← what to build (authoritative)
├─ CLAUDE.md                      ← this file (how to build)
├─ TESTING.md                     ← manual test matrix, run before every release
├─ docs/adr/NNNN-*.md             ← architecture decision records
├─ .github/workflows/             ← ci.yml (PR gate), release.yml (tag-triggered)
├─ scripts/                       ← guard-schema.sh, guard-version.sh (bash — see §11)
├─ gradle/libs.versions.toml      ← ALL dependency versions. No exceptions.
├─ build-logic/                   ← convention plugins (android-library, compose, hilt, room)
├─ version.properties             ← monotonic versionCode. Auto-incremented. Committed.
├─ keystore.properties            ← gitignored. Back up off-repo. Losing it = can't ship updates.
├─ app/
├─ core/
│  ├─ model  common  crypto  database  datastore  domain  data  designsystem  ui  testing
├─ feature/
│  ├─ onboarding  dashboard  inbox  entry  ledger  analytics  budget
│  ├─ categories  ingest  ocr  export  settings
│     └─ ingest/src/
│        ├─ main/          ← rule engine, dedupe, worker, adapters/NotificationAdapter
│        ├─ smsFull/       ← adapters/SmsAdapter + RECEIVE_SMS manifest entry
│        └─ playSafe/      ← no-op SMS adapter
├─ benchmark/                     ← Macrobenchmark + baseline profile generation
└─ testdata/
   ├─ sms/                        ← golden SMS corpus (input .txt + expected .json)
   ├─ notifications/              ← golden notification corpus (same expected-JSON format)
   └─ receipts/                   ← golden receipt images + expected line items
```

**Dependency rule (enforced by a Gradle check):**
- `:feature:*` → `:core:*` only. **Features never depend on features.**
- `:core:model` depends on nothing (pure Kotlin, no Android).
- `:core:domain` depends on `:core:model` + `:core:common` — **plus
  `androidx.paging:paging-common`, and nothing else from AndroidX** (ADR-0014).
  That artifact is the Kotlin/JVM half of Paging 3 (`PagingData`, `Pager`,
  `PagingSource`); it contains no `android.*` and the module still compiles and
  unit-tests off-device, which is the property this rule exists to protect.
  `paging-runtime` and `paging-compose` are the Android halves and belong to
  `:feature:*`. **This is a carve-out, not a precedent:** a second AndroidX
  coordinate proposed for `:core:domain` is the trigger to reopen ADR-0014, not
  to widen this bullet.
- `:app` wires everything; it contains no business logic.

---

## 4. Commands (Windows / PowerShell)

```powershell
.\gradlew assembleSmsFullDebug            # build debug APK
.\gradlew installSmsFullDebug             # install to connected device
.\gradlew testSmsFullDebugUnitTest        # unit tests
.\gradlew connectedSmsFullDebugAndroidTest # instrumented tests (device required)
.\gradlew :core:database:connectedAndroidTest  # migration tests specifically
.\gradlew verifyRoborazziSmsFullDebug     # screenshot diff check -- exactly what CI's `screenshot` job runs
.\gradlew recordRoborazziSmsFullDebug     # re-record goldens. REVIEW the diff first; never re-record blind (§12)
.\gradlew detekt lintSmsFullDebug         # static analysis
.\gradlew bannedApiCheck                  # `!!` / cacheDir bans (Laws 5 & 7)
.\gradlew restrictedPermissionCheck       # pins the EXACT permission set per source set (D-04, Law 6)
.\gradlew :benchmark:connectedBenchmarkAndroidTest  # macrobenchmark
.\gradlew generateBaselineProfile         # regenerate shipped baseline profile
.\gradlew assemblePlaySafeDebug           # Play-eligible flavour (no RECEIVE_SMS)
.\gradlew preMergeCheck                   # everything the CI gate runs — builds BOTH flavours
```

**Both flavours must build and pass tests on every PR.** `playSafe` is not a "later" deliverable; it's the Play-eligible build and it ships the same notification ingest path.

**Never run `adb uninstall`** during development — it destroys test data and masks BUG1. Use `installSmsFullDebug` (install-over-install) so you're continuously testing the upgrade path.

**`connectedAndroidTest` uninstalls both APKs when it finishes**, which is the same destruction arriving through a task you *do* have to run — it takes the app's vault with it, and the symptom on next launch is a Recovery screen, not an obvious "tests wiped your data". `gradle.properties` sets `android.injected.androidTest.leaveApksInstalledAfterRun=true` to stop it. Don't remove that line. Separately, instrumented tests must open their **own** database via `VaultSession`'s injected name and never the app's — sharing one file across test methods also surfaces as `NeedsRecovery`.

Debug builds use `applicationIdSuffix ".debug"` and coexist with release installs. This is deliberate. Don't remove it.

---

## 5. Code Conventions

**Kotlin**
- Explicit API mode on for `:core:*`.
- `!!` is a build error outside tests. Use `requireNotNull(x) { "why" }` or handle the null.
- No `GlobalScope`. Structured concurrency only.
- Dispatchers are injected (`@IoDispatcher`), never hardcoded — untestable otherwise.
- Sealed interfaces for state and events. Exhaustive `when`, no `else` branch on sealed types.
- `Result<T>` / typed error sealed classes at repository boundaries. No exceptions as control flow.

**Compose**
- Composables are **stateless**. State hoists to the ViewModel. A composable that owns business state is a bug.
- One `UiState` data class per screen, `@Immutable`, exposed as `StateFlow`.
- Events flow up as a single `(ScreenEvent) -> Unit` lambda.
- `collectAsStateWithLifecycle()` — never bare `collectAsState()`.
- `LazyColumn` always has `key` and `contentType`.
- Preview annotations on every top-level screen: `@PreviewScreenSizes @PreviewFontScale @PreviewLightDark`.
- Zero hardcoded colours, dimensions, or type sizes. Everything comes from `LfTheme`.
- **A control's label never wraps** (BUG9). Buttons/chips render their label `maxLines = 1, softWrap = false`, with no ellipsis. Two or more actions go in an `LfActionRow` (`FlowRow`) so the *container* wraps whole controls — a bare `Row` of three actions inside a card is the defect, and it shows up as "Delet / e".
- Insets: `enableEdgeToEdge()` + **`LfScaffold`**, never Material's `Scaffold` directly and never `WindowInsets.safeDrawing`. `safeDrawing` includes the IME, and consuming it on both the content and a pinned bottom bar subtracts the keyboard twice — measured on device, the bar rendered 8px tall at the top of the screen. `LfScaffold` consumes system bars + display cutout for layout and the keyboard exactly once via `imePadding()` on the scaffold. Never hardcode bar padding. A `bottomBar` therefore supplies only its own *visual* padding — `LfScaffold` has already inset it for the navigation bar, so a uniform `lg` on a pinned bar stacks 24dp on top of an inset that exists for the same purpose, and it comes out of the one thing on the screen that scrolls. Note the corollary when someone asks for a pinned control to sit lower: once its own padding is at `xs`, the control is *already* against the navigation bar and cannot move down at all. Any further height for the scrolling content has to come from the header — measure with `uiautomator dump` plus the nav bar's `mFrame` before changing numbers, rather than guessing which gap is the slack one.

**Visual design philosophy** — the owner's standing brief, restated after
several rounds of "this looks bloated". Read it as a constraint, not a mood:
every line below is something a change has already violated at least once.

- **Compact by default.** Medium-to-small boxes and text. A row that presents
  one thing plus its actions should cost about two lines of height, not a card
  with a header, a divider and a row of pill buttons. If a list of three items
  fills the screen, the item is too big — that list exists to be scanned.
- **One shape per screen.** All the sections of a screen use the same card
  container. Two shapes on one screen reads as two designs, and it is obvious
  the moment the user switches tabs. Extract a private container composable
  (see `TaxonomyCard`) rather than repeating a `Modifier` chain.
- **Hairline border over elevation** for anything list-sized. At that scale
  elevation reads as a smear rather than depth, and a border is what keeps
  adjacent structure (nesting rails, tree stubs) legible against the card edge.
- **In-card actions are `LfButtonStyle.Inline`,** not `Outlined`/`Filled`
  pills. Pills are for a screen's primary action, not for three actions inside
  a row. This is also usually what decides whether the actions fit on one line.
- **No odd placements.** Actions sit on one edge, aligned — `LfActionRow` with
  an explicit alignment, never a stray `Row` with hand-tuned spacers. The
  heading gets its own line rather than competing with the buttons for it: a
  name inside the action row makes the row wrap mid-actions, which is how the
  category card ended up taller than the one it replaced.
- **The scrolling content is the screen's purpose; chrome pays for it.** Headers
  and pinned bars are trimmed toward the scale's small end (`sm`/`xs`), not the
  comfortable middle. A gap between stacked header bands is charged to the list
  once per band.
- **Proportions are measured, not guessed.** Before changing a number, dump the
  real geometry (`uiautomator dump`, plus the nav bar's `mFrame` when a pinned
  bar is involved) and state the before/after in the commit. "Looks tight" is
  not a measurement, and space that appears wasted is often a system inset.
- **It must survive font scale 2.0 and RTL.** Elegant at 1.0 and broken at 2.0
  is broken. Degrade by wrapping whole controls, never by clipping a label
  (BUG9) — verify on device, not only in previews.
- **The palette does not change** to solve a layout problem. Reach for spacing,
  type scale and hierarchy first; colour is not a fix for a crowded row.


**Charts and analytics** — the full catalogue and its phasing live in
`docs/DATAVIZ-PLAN.md`; `SPEC.md` §5.6 is the summary. These are the build
rules, and every one of them has a failure mode rather than a preference behind
it.

- **Hand-rolled `Lf*` Canvas primitives in `:core:designsystem`. No charting
  dependency** (ADR-0005). Adding a coordinate to `libs.versions.toml` for a
  chart is the visible signal that ADR is being reopened. They go in
  `:core:designsystem` and not `:feature:analytics` because that is where the
  Roborazzi harness lives and where a chart therefore inherits the fontScale-2.0
  gate mechanically rather than by inspection.
- **A chart never holds the series.** §11 forbids handing one more points than
  it has horizontal pixels, so a zoom or pan is a **re-query of `daily_rollup`
  at the new resolution**, not a transform over held data. The viewport state
  belongs to the ViewModel that issues the query. Getting this backwards is how
  a 5Y view ends up loading 1,825 points to draw 300 of them.
- **Analytics reads `daily_rollup`; drill-downs read base tables via Paging 3.**
  Two named exceptions, both of which must stay exceptions: **recurring
  detection** needs the sequence of individual occurrence dates per merchant,
  which a daily sum has thrown away; and the **P4 item-grain views** read an
  item-observation view, because a unit price is a ratio and cannot live in a
  `SUM` table.
- **Never widen `daily_rollup` to carry unit prices, item names or any ratio.**
  It sums money per dimension. "Just add it to the rollup" is the obvious wrong
  move, and it parks a non-money real number next to a Law 3 column.
- **Every rollup statement binds `ledger`** (Law 2). `daily_rollup` has no
  protective views the way `ledger_entry` does, so
  `LedgerIsolationTest.noQueryTouchesDailyRollupWithoutBindingALedger` is the
  only thing standing between a plausible-looking `SUM(sum_minor)` and a figure
  that nets a month of income against a month of spending. Budgets sharpen it:
  §5.7 scopes them to debit and `budget` has no `ledger` column, so "debit only"
  is *entirely* a property of the read. A literal `'DEBIT'` in the SQL satisfies
  the guard and is preferred to a parameter with one legal value.
- **No chart, axis, legend or total may combine the two books.** There is no
  netted figure anywhere in this app, and the two-book parallel view exists to
  make that separation legible rather than to apologise for it.
- **Money is `Long`; chart coordinates are not.** Law 3 bans `Float`/`Double`
  for a monetary amount. Pixel positions, σ/μ ratios in recurring detection
  and price-index values are legitimately real-valued and always were.
- **The chart is usually not the content.** In most of these surfaces the ranked
  list is what the user reads and the graphic is orientation. Size it that way —
  a donut that fills the viewport above a list the user actually came for is the
  compactness brief violated with a circle.
- **Goldens at 1x and 2x for every chart, reviewed, never blind-recorded**
  (§12). A chart that clips a label at 2.0 is BUG9 wearing a new hat.
- **Semantics per segment** (`SPEC.md` §9.6). A donut slice a screen reader can
  name. Charts are data, not decoration, and `clearAndSetSemantics` on one is a
  bug rather than a shortcut.
- **Tick selection is unit-tested independently of rendering** (`AxisTicksTest`).
  It is the one piece here with a correct answer that does not depend on how it
  looks — ticks at 0/117/234 are wrong in a way no screenshot diff will call.

**Room**
- Every DAO returns `Flow<T>` for reads, `suspend` for writes.
- **Reads go through the `debit_entries` / `credit_entries` views, never `ledger_entry` directly** (ADR-0002). Any query that does name the base table takes a `ledger: LedgerType` parameter — no overload omits it. `LedgerIsolationTest` fails the build otherwise.
- `@Transaction` on any multi-write operation. Approval is a single transaction: insert entry + line items + update pending status + update rollups.
- Schema JSONs in `core/database/schemas/` are **committed**. Changing one without a migration fails CI.

**Naming**
- Use cases: `VerbNounUseCase` with a single `operator fun invoke`.
- ViewModels: `ScreenNameViewModel`. State: `ScreenNameUiState`. Events: `ScreenNameEvent`.
- Design system composables prefixed `Lf` (`LfCard`, `LfChip`).
- Tests: `MethodName_condition_expectedResult` or `BugN_description`.

---

## 6. Adding a Feature (the standard loop)

1. Read the relevant `SPEC.md` section. If it's ambiguous, **ask** — don't infer.
2. If the change alters architecture or a dependency, write an ADR in `docs/adr/` first.
3. `:core:model` → `:core:domain` (interface + use case) → `:core:data` (impl) → `:core:database` (if schema) → `:feature:x` (UI).
4. Schema change? → new `Migration` class + `MigrationTest` + regenerated schema JSON, in the same commit.
5. Write tests **alongside** the code, not after. Parser/OCR work adds golden fixtures to `testdata/`.
6. Run `.\gradlew preMergeCheck` locally before declaring done.
7. Update `SPEC.md` if behaviour diverged from the spec. **The spec is not allowed to go stale.**

---

## 7. Danger Zones — read before touching

### `:core:crypto`
The DEK is multi-wrapped by two factors: Android Keystore (KEK-A) and the 24-word recovery phrase (KEK-B, mandatory). This is what prevents permanent data loss on factory reset or device migration. KEK-C (optional passphrase) is **dropped** — ADR-0011. The `wrapped_dek_pass.bin` slot and `KekId.PASSPHRASE` stay reserved and unwritten; that is deliberate, not an oversight.

- **Never** change the phrase→key derivation. It is pinned byte-for-byte in `SPEC.md` §7.2 and locked by committed golden test vectors. If that test fails, **the code is wrong — do not re-record the fixture.** Re-recording it silently orphans every backup a user has ever made.
- **Never** write a `.lfbk` whose header isn't passed as GCM AAD (`SPEC.md` §5.9). An unauthenticated header lets an attacker steer the restore path.
- **Never** make the Keystore path the only wrap.
- **Never** set `setUserAuthenticationRequired(true)` on the DEK-wrapping key — biometric re-enrollment would nuke the user's data.
- **Never** add a third wrap without a superseding ADR. Backups are phrase-derived only, and a device-local convenience factor must never become the weakest link protecting a file that could leave the device.
- **Never** add a "skip" or "remind me later" to the onboarding word challenge.
- **Never** wipe the database on a decryption failure. Route to the Recovery screen instead (`SPEC.md` §7.3).
- Validate the BIP-39 checksum **before** running PBKDF2/HKDF — otherwise a typo costs the user 2048 HMAC-SHA512 rounds and looks like a hang.
- Any change here requires the backup→wipe→restore round-trip test to pass **before** the commit.

### `:feature:ingest`
- Capture adapters (SMS receiver, `NotificationIngestService`) do exactly one thing: normalize to `RawIngestEvent`, persist it, enqueue `ParseIngestWorker`. No parsing, no DB joins, no branching on source.
- The notification package allowlist filter runs **before** any notification body is read. Never log or persist content from a non-allowlisted package — this is a stated privacy guarantee, not an implementation detail.
- Cross-source dedupe is mandatory. A single UPI payment commonly fires both a bank SMS and a GPay notification; producing two pending rows is a bug with a named test (`Dedupe_SameTxnAcrossSources_ProducesOnePending`).
- Suppressed duplicates are **retained and visible** under the Inbox "Suppressed" filter. Never silently discarded.
- **A suppressed duplicate never notifies** (§5.1, P2-7). "Retained and visible" and "announced" are different promises: one payment fires a bank SMS *and* a payment-app notification, and buzzing twice is the dedupe layer defeated through a different surface. `ParseCapturedMessages` posts on a `Created` outcome only — and *cancels* `supersededPendingId`, because the sparse message usually lands first, is correctly announced as the only candidate there is, and then loses. `SuppressedCandidateDoesNotNotifyTest` guards both halves.
- **Anything reachable with no Activity alive must open the vault itself.** Capture adapters, `ParseIngestWorker` and the `[Approve]`/`[Discard]` notification actions all run in a process with no UI, and `VaultSession.requireDatabase()` *throws* there. Both ingest and pending repositories route through `openForBackgroundWork()` — no new wrap, no new key material, the same `openOnLaunch()` the UI calls, which §7 permits by forbidding `setUserAuthenticationRequired(true)` on the DEK-wrapping key. The failure mode is the reason this is written down: the throw lands in a `runCatching` and comes back as a clean `false`, so the action reports success and does nothing (BUG13, and BUG12's `d88ca85` before it). **If you add a repository method a background caller can reach, it opens the vault or it lies.**

### Destroying ledger data
`PurgeDeletedEntriesUseCase` is the only thing in the app that removes a
committed `ledger_entry` row from the file, and the only irreversible operation
anywhere in it. `LedgerSingleWriterTest` guards **all four** doors into that
table — `approve`, `softDeleteEntry`, `restoreEntry`, `purgeDeletedEntries` (and
its per-row `purgeDeletedEntry`) — and a fifth would need the same guard on the
day it appears. Restoring is guarded too, for all that it destroys nothing: it
is a write that makes past totals change again.

- **Every one of those statements binds `:ledger`.** The bin shows both books
  at once (ADR-0015), so it is the one screen where a write can be issued
  against the wrong ledger — and with the predicate in place that affects no
  rows and returns `EntryNotFound` rather than quietly hitting something else.
- **The per-row purge also binds `deleted_at IS NOT NULL`.** Without it, that
  statement would destroy any entry by id, live or not. The bin only ever shows
  binned rows, so nothing in the UI would have caught the difference.

- **Never make the purge reachable without a `Warning` confirmation** that names
  the count. It is one tap from a Settings row and cannot be undone.
- **Never offer to back up first as though the app could.** Backups are
  phrase-derived (ADR-0011) and the app never holds the phrase; the dialog tells
  the user to export, it does not pretend to do it for them.
- **`VACUUM` runs outside a transaction, after a WAL checkpoint.** SQLite
  refuses it inside one, and an uncheckpointed WAL means rewriting a file that
  does not yet contain the deletes. It rewrites the whole encrypted database, so
  a mistake here does not fail loudly — it surfaces as an unreadable vault on
  the user's next launch. `PurgeDeletedEntriesTest` opens and reads the vault
  afterwards for exactly that reason.

### `:core:database` migrations
- A pre-migration snapshot is written and verified before any migration runs (`PreMigrationGuard`). Don't remove this. It is a **copy of the encrypted database file, not a `.lfbk`** — ADR-0019: a `.lfbk` is phrase-derived and the app never holds the phrase at launch, so the `.lfbk` form this line used to claim was never implementable. Don't "fix" it back.
- **The snapshot is not a backup and must never be presented as one.** It is not portable, it dies with the device, and it is deleted on the first clean launch after the migration. The same rule as the purge dialog: the app tells the user to export, it does not pretend it can back up for them.
- Migrations use `CREATE new / INSERT SELECT / DROP old / RENAME`, not `ALTER` chains — `ALTER` chains can half-apply and leave a corrupt schema.
- `PRAGMA foreign_key_check` after every migration; a violation aborts and rolls back.

### SMS receiver
- The receiver has ~10 seconds before the system kills it. It does exactly one thing: **write the raw SMS to `sms_raw` and enqueue a Worker.** No parsing, no network, no DB joins in the receiver.
- Never call `abortBroadcast()` — other SMS apps must still receive the message.
- Unparseable SMS from an allowlisted sender still creates a `PENDING` row with `confidence = 0`. **Never silently drop a financial SMS.**

### Backup writer
Atomic only: write `.tmp` → fsync → **decrypt-and-parse to verify** → rename. A backup that hasn't been round-trip verified is not a backup, and `lastBackupAt` must not be updated for it.

### Draft persistence
Entry-form state persists to Room on every field change (300 ms debounce). If you find yourself holding a half-built entry only in a ViewModel field, that's BUG6 — fix it, don't ship it.

---

## 8. Performance Rules

Targets live in `SPEC.md` §11. Practical rules while coding:

- Never load a full ledger into memory. Paging 3, always.
- Charts get pre-binned data — never more data points than horizontal pixels.
- Analytics reads `daily_rollup`, not `ledger_entry`. Drill-downs read base tables via Paging.
- Watch the Compose compiler stability report. A new unstable parameter in a `LazyColumn` item composable is a regression — fix the type, don't wrap it in `remember`.
- Baseline profiles are shipped and regenerated whenever startup or the nav graph changes materially.
- Anything > 1 ms on the main thread is suspicious. `StrictMode` runs with `penaltyDeath` in debug — if it kills your build, that's the system working.

---

## 9. What NOT To Do

| ❌ | ✅ |
|---|---|
| `fallbackToDestructiveMigration()` | explicit `Migration` + test |
| `Float`/`Double` for money | `Money(minor: Long)` |
| Auto-inserting parsed SMS into the ledger | `pending_transaction` + user approval |
| A "Total balance" that nets credits against debits | two separate totals, always |
| `android.widget.Toast` | Material `Snackbar` (`LfSnackbar`) |
| MPAndroidChart / any View-based chart | Compose-native charts |
| Any charting dependency at all | hand-rolled `Lf*` Canvas (ADR-0005) |
| Handing a chart the whole 5Y series | pre-binned to pixel width; zoom re-queries |
| A unit price or item name in `daily_rollup` | item-observation view (P4) |
| `SUM(sum_minor)` without binding `ledger` | a literal `'DEBIT'`/`'CREDIT'` or `:ledger` |
| A donut bigger than the list beside it | the graphic orients; the list is the content |
| Apache POI for XLSX | lightweight writer (see ADR-0004) |
| `allowBackup="true"` | `false` + own `.lfbk` backup |
| Storing anything in `cacheDir` | `filesDir` |
| Hardcoded versions in `build.gradle.kts` | `libs.versions.toml` |
| Dynamic versions (`1.2.+`) | pinned versions |
| `adb uninstall` during dev | `installDebug` (install-over-install) |
| Feature module depending on another feature | shared code → `:core:*` |
| Hardcoded colours/dp/sp | `LfTheme` tokens |
| `!!` | `requireNotNull` with a message |
| Business logic in a Composable | ViewModel + use case |
| A `Row` of 3 action buttons in a card | `LfActionRow`; labels never wrap (BUG9) |
| Silently dropping an unparsed bank SMS | `PENDING` with `confidence = 0` |
| Wiping the DB when decryption fails | Recovery screen |
| Fetching an FX rate | user-entered `fx_rate_micro`, or require base amount at review |
| Summing mixed currencies in analytics | `amount_minor` is always base currency — there is nothing to mix |
| Encrypting `.lfbk` with any user-chosen secret | phrase-derived key only |
| `if (source == SMS)` outside an adapter | source-agnostic pipeline |
| Reading a notification before the allowlist check | filter first, then touch the body |
| Shipping only `smsFull` | both flavours build and test on every PR |

---

## 10. Communication Protocol

- **Ask before assuming** on: schema shape, encryption behaviour, ledger semantics, anything in §7 Danger Zones.
- **Propose, don't unilaterally adopt**, any new dependency. Justify size, maintenance status, and why the platform SDK isn't enough.
- Multi-step work: state the plan, get a nod, then execute. Don't generate 15 files off one ambiguous sentence.
- When you hit a genuine spec gap, add it to `SPEC.md` §16 Open Questions rather than inventing an answer.
- Flag it loudly when a requested change would violate one of the Seven Laws. Don't quietly work around them.

---

## 11. Git & CI/CD

**Repo hygiene rules that are not negotiable:**

- **Never commit** `keystore.properties`, `local.properties`, `*.jks`, or any secret. The `guards` job greps for them and fails the build. If one lands in history, the key must be rotated — and rotating the release key orphans every existing install.
- **Never edit a committed Room schema JSON.** They are append-only. `scripts/guard-schema.sh` fails the PR. Bump the DB version and add a new one.
- **Never bump `versionCode` downward or sideways.** `scripts/guard-version.sh` compares against the last release tag.
- **Never add `continue-on-error` to the `instrumented` job.** It runs the migration chain and the backup round-trip. Making it advisory turns the durability guarantees in `SPEC.md` §7 into decoration. If it's flaky, fix the flake.
- Conventional Commits (`feat:`, `fix:`, `perf:`, `db:`, `chore:`). `db:` signals a schema-touching PR and triggers closer review. Release notes are generated from these.
- Squash-merge only. `main` stays linear and always releasable.

**Windows → Linux CI traps (you're on Win11, every runner is Linux):**

| Trap | Guard |
|---|---|
| CRLF endings breaking parser fixtures | `.gitattributes` (`* text=auto eol=lf`, `testdata/** -text`). Run `git config --global core.autocrlf false`. |
| `gradlew` losing its exec bit | `git update-index --chmod=+x gradlew`, committed once |
| Case-only filename collisions (fine on Windows, fatal on Linux) | `guards` job runs `git ls-files \| tr A-Z a-z \| sort \| uniq -d` |
| 260-char path limit | `git config --global core.longpaths true` |

**`bash` on this box is not Git Bash — check before you trust it.** On a stock Windows 11, `bash` resolves to `C:\Windows\System32\bash.exe`, which is the **WSL** launcher. WSL is a different Linux, with a different `git`, a different view of the filesystem (`/mnt/d/...`), and a very good chance of a `detected dubious ownership` failure against a repo owned by the Windows user. The guard scripts assume Git Bash.

Verify with `(Get-Command bash).Source`. If it points at System32 or WindowsApps, put Git Bash's `bin` ahead of it on PATH (`<git-install-root>\bin`; on this machine `D:\Software\Git\bin`) or call it explicitly.

**Before pushing**, run the guards locally — they're seconds, and they save a round-trip:
```powershell
& "$((Get-Item (Get-Command git).Source).Directory.Parent.FullName)\bin\bash.exe" scripts/guard-schema.sh
```
```powershell
& "$((Get-Item (Get-Command git).Source).Directory.Parent.FullName)\bin\bash.exe" scripts/guard-version.sh
```
```powershell
.\gradlew preMergeCheck
```

**What CI cannot catch.** Automation stops at real-device behaviour. `TESTING.md` still runs before every release, specifically for BUG2 (OTA/OS-update survival — no runner simulates a kernel update), BUG1 (install-over-install across signing configs), OEM battery-killer behaviour on `NotificationIngestService`, and the 60fps *feel* that Macrobenchmark numbers only approximate. Benchmarks run on a **self-hosted runner** — your Win11 box with the phone attached — because emulator frame timings are noise.

**When a real bank SMS or UPI notification fails to parse**, it becomes a permanent golden fixture in `testdata/`. The corpus only grows.

---

## 12. Definition of Done

A change is done when **all** of these are true:

- [ ] Builds clean: `.\gradlew preMergeCheck`
- [ ] Unit tests added/updated and passing
- [ ] Migration test added if the schema changed
- [ ] Backup→restore round-trip still green
- [ ] Screenshot diffs reviewed (not blindly re-recorded)
- [ ] Previews render at fontScale 2.0 and in RTL without overlap
- [ ] No new StrictMode violations in debug
- [ ] No Compose stability regressions in the compiler report
- [ ] `SPEC.md` updated if behaviour changed
- [ ] `scripts/guard-schema.sh` and `scripts/guard-version.sh` pass locally
- [ ] Both flavours (`smsFull`, `playSafe`) build
- [ ] Manually verified on the physical device — including a force-stop → relaunch cycle
