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
- `:core:domain` depends on `:core:model` + `:core:common` only.
- `:app` wires everything; it contains no business logic.

---

## 4. Commands (Windows / PowerShell)

```powershell
.\gradlew assembleSmsFullDebug            # build debug APK
.\gradlew installSmsFullDebug             # install to connected device
.\gradlew testSmsFullDebugUnitTest        # unit tests
.\gradlew connectedSmsFullDebugAndroidTest # instrumented tests (device required)
.\gradlew :core:database:connectedAndroidTest  # migration tests specifically
.\gradlew verifyRoborazziDebug            # screenshot diff check
.\gradlew recordRoborazziDebug            # re-record golden screenshots (review diffs!)
.\gradlew detekt lintSmsFullDebug         # static analysis
.\gradlew bannedApiCheck                  # `!!` / cacheDir bans (Laws 5 & 7)
.\gradlew :benchmark:connectedBenchmarkAndroidTest  # macrobenchmark
.\gradlew generateBaselineProfile         # regenerate shipped baseline profile
.\gradlew assemblePlaySafeDebug           # Play-eligible flavour (no RECEIVE_SMS)
.\gradlew preMergeCheck                   # everything the CI gate runs — builds BOTH flavours
```

**Both flavours must build and pass tests on every PR.** `playSafe` is not a "later" deliverable; it's the Play-eligible build and it ships the same notification ingest path.

**Never run `adb uninstall`** during development — it destroys test data and masks BUG1. Use `installSmsFullDebug` (install-over-install) so you're continuously testing the upgrade path.

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
- Insets: `enableEdgeToEdge()` + `Scaffold` + `WindowInsets.safeDrawing`. Never hardcode bar padding.

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

### `:core:database` migrations
- A pre-migration `.lfbk` backup is written and verified before any migration runs. Don't remove this.
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
