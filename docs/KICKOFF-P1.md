# LedgerFlow — P1 Kickoff Prompt

> **How to use:** open Claude Code in the repo root and paste everything below the line as your first message.

---

## ROLE & AUTHORITY

You are the lead engineer on **LedgerFlow**, an Android-only, offline-first, encrypted expense tracker. Two documents govern you:

- **`SPEC.md`** (repo root) — what to build. Authoritative on requirements, schema, security model, phasing.
- **`CLAUDE.md`** (repo root) — how to build. Authoritative on conventions, module boundaries, the Seven Laws, danger zones.

**Read both in full before writing a line.** Where they conflict with anything I say casually in chat, they win — tell me and I'll amend the doc rather than you silently diverging. Where they're silent or ambiguous, **ask**. Do not infer.

`SPEC.md §2.5` (Decision Log) and `docs/adr/` are settled. Do not relitigate the stack, currency model, recovery model, ingest strategy, ledger partitioning (ADR-0002), key rotation (ADR-0009) or crypto libraries (ADR-0010).

---

## WHERE THE PROJECT STANDS

**Phase 0 is complete and verified on a physical device.** Twelve commits on `main`; `origin/main` is current except the last one.

| Layer | State |
|---|---|
| Build | Gradle 9.7.0, AGP 9.3.1, Kotlin 2.3.21, KSP 2.3.11, JDK 17 toolchain, `compileSdk 37` / `targetSdk 36` / `minSdk 26` |
| Modules | 23 wired modules + `:benchmark`, both `smsFull` and `playSafe` flavours build |
| build-logic | 6 convention plugins: `android.library`, `.compose`, `.hilt`, `.room`, `.feature`, `jvm.library` |
| `:core:model` | `Money` (Long minor units), `LedgerType`, `EntrySource`, `PaymentMethodType`, `LineItemKind`, ISO-4217 exponents |
| `:core:common` | hand-rolled UUIDv7 generator |
| `:core:crypto` | BIP-39, HKDF (RFC 5869), PBKDF2 (RFC 2898), pinned `KeyDerivation` with golden vectors, AES-GCM, wrapped-DEK blobs, Keystore KEK-A, `DekManager` unlock flow, `.lfbk` container |
| `:core:database` | SQLCipher + Room schema v1 (6 tables + 2 ledger views), migration harness, WAL checkpoint, canary, backup/restore |
| `:core:designsystem` | `LfTheme` (dark default + light mirror), typography with tabular figures, 4 atoms, WCAG contrast tests |
| `:feature:onboarding` | Currency → phrase → word challenge → Recovery Kit → backup location, edge-to-edge |
| Verification | 174 unit tests, 0 failures. Instrumented on SM-S721B (Android 16 / API 36): crypto 5, database 19, all green, both flavours |

**The Phase 0 exit gate passes on device:** seed every table → `.lfbk` backup → delete the database **and** the Keystore key → recover from the 24 words alone → row-level content equality across every table.

### What Phase 0 did NOT finish — read this before planning

1. **The app does not actually open the database.** `MainActivity` renders onboarding and nothing else. Nothing wires `DekManager` → `LedgerFlowDatabaseFactory` → canary check. The §7.3 unlock flow exists as library code with no caller. **This is P1's first prerequisite.**
2. **SAF pickers are stubs.** Recovery Kit save and backup-location grant emit events with empty URIs; no `ActivityResultContracts` are wired.
3. **`TESTING.md` does not exist**, though `CLAUDE.md §3` and `SPEC.md §15.8` both reference it as the pre-release gate.
4. **No bundled font.** `SPEC.md §9.2` specifies Inter or Manrope; the platform default is in use. Needs the binary plus its licence.
5. **Primary CTAs scroll with content** rather than being pinned to the scaffold's bottom bar. Reachable, but at 2.0× font scale a below-the-fold CTA is an accessibility problem.
6. **`:app` hand-maintains build config** that duplicates `AndroidConventions.configureAndroidLibrary`. It deserves an `ledgerflow.android.application` convention plugin (there's a `TODO(step7)` in `app/build.gradle.kts`).
7. **No Hilt anywhere yet.** The convention plugin exists and is unused; `OnboardingViewModel` is constructed directly.
8. **No `:core:domain` / `:core:data` content.** Both modules are empty. P1 is where the repository/use-case layer gets built.

---

## SCOPE OF THIS SESSION: **P1 ONLY**

Per `SPEC.md §13`:

> **P1 — Manual core.** Manual entry, categories/subcategories, merchants, payment methods, both ledgers, Ledger list with filters, CSV export, `TransactionIngestSource` abstraction + `smsFull`/`playSafe` flavour skeleton (both compiling, both installable).
>
> **Exit criteria:** can fully use the app without SMS/OCR. Both flavours build in CI.

Concretely, that means:

- **Wire the unlock flow** (§7.3): Keystore → canary → database, with the Recovery screen on failure. Never a wipe.
- **`:core:domain`** — repository interfaces + use cases. `ApproveTransactionUseCase` is the *only* thing that may insert into `ledger_entry` (Law 1).
- **`:core:data`** — repository implementations and mappers.
- **Hilt** throughout, using the existing convention plugin.
- **Schema v2** — `draft_entry` at minimum (see Open Questions below). New `Migration`, `MigrationTest`, committed schema JSON, all in one commit.
- **`:feature:entry`** — manual entry, ≤4 taps for a repeat expense, both ledgers, multi-line-item editor, **draft persistence to Room on every field change, 300 ms debounce** (BUG6).
- **`:feature:categories`** — category/subcategory/group CRUD, merchant management, payment methods.
- **`:feature:ledger`** — debit and credit lists with filters and search, **Paging 3** (§11: never load a full ledger into memory).
- **`:feature:export`** — CSV export via SAF.
- **`:feature:ingest`** — the `TransactionIngestSource` abstraction and the flavour source-set skeleton only. **No parsing, no receivers, no notification listener** — that is P2.

**Do not build:** SMS or notification parsing, OCR, analytics, charts, budgets, XLSX. Those are P2–P5.

---

## ENVIRONMENT — READ BEFORE RUNNING COMMANDS

| Fact | Implication |
|---|---|
| **Windows 11**, PowerShell | `.\gradlew`, not `./gradlew`. Chain with `;` or run separately. |
| **No `java` on PATH** | Set `$env:JAVA_HOME = "D:\Software\Android App development\jbr"` before every Gradle invocation. Android Studio lives at `D:\Software\Android App development`. |
| **Android SDK** | `C:\Users\swaro\AppData\Local\Android\Sdk`. `adb` is at `platform-tools\adb.exe`, not on PATH. |
| **Git Bash** | `D:\Software\Git\bin\bash.exe`. Bare `bash` resolves to **WSL**, which is a different Linux with a different `git` — the guard scripts assume Git Bash. |
| **Physical device** | Samsung SM-S721B (Galaxy S24 FE), Android 16 / API 36, arm64-v8a. Confirm with `adb devices -l` before any `connected*` task. It drops off when the screen locks. |
| **Norton TLS interception** | Norton MITMs HTTPS. The JBR does not trust its root, so Gradle downloads fail with a misleading "plugin not found". Worked around by `~/.gradle/gradle.properties` pointing at `~/.gradle/truststore-norton.jks` (a copy of the JBR cacerts plus Norton's root). **Machine-local, not in the repo.** If dependency resolution suddenly fails, check that file still exists. |
| **Never run `adb uninstall`** | Destroys test data and masks BUG1. Use `installSmsFullDebug`. |

The legacy pre-rewrite app is still installed on the device as `com.ledgerflow`. Debug builds install as `com.ledgerflow.debug` and `com.ledgerflow.playsafe.debug`.

---

## HARD-WON FACTS — do not rediscover these

These cost real time in Phase 0. They are in code comments too, but read them now.

**AGP 9 / Kotlin**
- AGP 9 has **built-in Kotlin**. Applying `org.jetbrains.kotlin.android` alongside it is a hard error. Android modules must not apply it.
- The Compose Compiler Gradle plugin **is** still required (`org.jetbrains.kotlin.plugin.compose`), despite the above.
- `CommonExtension` no longer exposes `flavorDimensions`, `productFlavors` or `isCoreLibraryDesugaringEnabled`. Bind the concrete `LibraryExtension`.
- `android.sourceSets.getByName(...)` throws `ClassCastException`. `variant.androidTest` is gone. `variant.deviceTests[..].sources.assets.addStaticSourceDirectory(...)` is callable but **silently merges nothing** — Room schemas are synced into `src/androidTest/assets` by a `Sync` task instead.
- **KSP major.minor tracks Kotlin major.minor.** KSP 2.3.11 targets Kotlin 2.3.20. Do not bump Kotlin alone — Room and Hilt codegen will break.
- Any module consuming the `:core`/`:feature` libraries must enable core library desugaring, or the AAR metadata check fails.

**Tooling limits**
- **detekt cannot enforce the `!!` ban here.** `UnsafeCallOnNullableType` needs type resolution, and detekt 1.23.8 only creates its type-resolving Android tasks when the Kotlin Android plugin is applied — which AGP 9 forbids. Six rules in `config/detekt/detekt.yml` are inert and marked as such. The `!!` and `cacheDir` bans are enforced by the regex `bannedApiCheck` Gradle task and a matching step in `ci.yml`.
- **Room's `@Query` has BINARY retention.** A reflection-based lint over DAO annotations finds nothing and passes vacuously. `LedgerIsolationTest` scans DAO **source** instead, and has a test asserting the scanner finds something.
- Android Lint does not treat `local.properties` as a task input, so a stale failure survives the fix until `--rerun-tasks`.
- Truth's `containsExactly` returns `Ordered`, so an expression-body `@Test fun x() = runBlocking { ... }` ending in one is non-`void` and JUnit rejects the whole class.
- `guard-schema.sh` matches the destructive-migration **call form**, and cannot tell a comment from code. Do not spell that API's name with parentheses in a comment.
- Parallel dexing of many `androidTest` variants has produced a transient `CompilationFailedException`. It passed on retry. If it recurs under load, fix the flake rather than retrying.

**Conventions that bit**
- Explicit API mode is on for `:core:*`. Every public declaration needs explicit visibility and return type.
- `@Preview` composables are exempted from `UnusedPrivateMember` in the detekt config.
- Compose clips tall ascenders when `lineHeight` is tight; all `LfTypography` styles carry a centred `LineHeightStyle` and `includeFontPadding = false`. Match that for any new style.

---

## OPEN QUESTIONS THAT GATE P1 — ask me, do not invent

1. **`draft_entry` has no schema** (`SPEC.md §16` Q6). BUG6 requires drafts to persist to Room, but §6.1 defines no such table. One row per in-flight entry keyed by a client id, or a single singleton draft? This is a schema decision and it blocks `:feature:entry`.
2. **KEK-C (optional passphrase) was deferred to P1** by `SPEC.md §7.2` and ADR-0010. ADR-0010 notes that if the answer is "ship a native `.so` for a convenience feature", that is itself an argument for dropping it. **Decide before implementing.** It must never protect a `.lfbk`.
3. **Recovery Kit is written in plaintext** (`SPEC.md §16` Q8) — the master secret for every backup, to shared storage. Plaintext, password-protected PDF, or plaintext plus an explicit confirmation? This is a product decision.
4. **Is `app_meta.canary` load-bearing?** (`SPEC.md §16` Q9). It is implemented so the question can be answered with evidence.

Questions 1 and 2 block real work. Raise them in your first response.

---

## RULES OF ENGAGEMENT

- **Commit incrementally**, Conventional Commits, one logical change per commit. `db:` prefix for anything schema-touching.
- **Tests alongside code**, not after. A step isn't done when it compiles.
- **Schema changes ship `Migration` + `MigrationTest` + regenerated schema JSON in the same commit.** `scripts/guard-schema.sh` enforces it; v1 is exempt from needing a migration, v2 is not.
- **Propose, never unilaterally adopt, a new dependency.** Justify size, maintenance status, and why the platform SDK isn't enough. The APK budget is under pressure already.
- **If a request would violate one of the Seven Laws, say so loudly.** Don't quietly work around them.
- **Flag over-engineering, including mine.**
- **Run the verification commands and report actual output. Never claim green without showing it.** Phase 0 found several defects precisely because tests were run rather than assumed — including a bypass of the word challenge and a non-transactional restore.
- Prefer fixing the code over loosening a guard. Every detekt config change in this repo has a written justification; match that bar.

Verification commands:
```powershell
$env:JAVA_HOME = "D:\Software\Android App development\jbr"
.\gradlew preMergeCheck
.\gradlew :core:database:connectedSmsFullDebugAndroidTest
& "D:\Software\Git\bin\bash.exe" scripts/guard-schema.sh
```

---

## FIRST RESPONSE

Do exactly this, nothing more:

1. Confirm you've read `SPEC.md` and `CLAUDE.md` in full, plus `docs/adr/0002`, `0009`, `0010`.
2. Restate the Seven Laws in your own words — compressed, not copy-pasted.
3. Report the current build state: run `preMergeCheck` and `adb devices -l`, and show the actual output.
4. Give me your proposed P1 breakdown — ordered steps with a stopping point after each — and tell me which of the four Open Questions you need answered before you can start.
5. Stop.
