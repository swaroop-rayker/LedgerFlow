# LedgerFlow — Kickoff Prompt

> **How to use:** put `SPEC.md`, `CLAUDE.md`, `.gitattributes`, `.gitignore`, `.github/`, and `scripts/` in an empty repo root. Open Claude Code in that directory. Paste everything below the line as your first message.

---

## ROLE & AUTHORITY

You are the lead engineer on **LedgerFlow**, an Android-only, offline-first, encrypted expense tracker. Two documents in this repo govern you:

- **`SPEC.md`** — what to build. Authoritative on requirements, schema, security model, and phasing.
- **`CLAUDE.md`** — how to build. Authoritative on conventions, module boundaries, the Seven Laws, and danger zones.

**Read both in full before you write a single line.** Where they conflict with anything I say casually in chat, they win — tell me and I'll amend the doc rather than you silently diverging. Where they're silent or ambiguous, **ask**. Do not infer.

Everything in `SPEC.md §2.5` (the Decision Log) is settled. Do not relitigate the stack, the currency model, the recovery model, or the ingest strategy.

---

## SCOPE OF THIS SESSION: **PHASE 0 ONLY**

Per `SPEC.md §13`, Phase 0 is the foundation:

- Module skeleton, convention plugins, version catalog
- Encrypted Room + SQLCipher wiring
- Key management (`SPEC.md §7.2` — multi-wrapped DEK)
- Onboarding: base-currency selection, 24-word recovery phrase, word challenge, Recovery Kit export
- Backup → wipe → restore round-trip, **verified by an automated test**
- Migration test harness
- Design system foundations (theme, tokens, 4–5 atoms — not the full component inventory)
- CI green on both flavours

**Do not build:** manual entry UI, SMS/notification ingest, OCR, analytics, charts, budgets, or export beyond the `.lfbk` backup. Those are P1–P5. If you find yourself writing a `LazyColumn` of transactions, you've drifted.

**Exit criteria for Phase 0:** the backup→wipe→restore test passes on a device, the migration harness works, and `preMergeCheck` is green. Nothing else matters yet.

---

## ENVIRONMENT — READ THIS BEFORE RUNNING COMMANDS

| Fact | Implication |
|---|---|
| **Windows 11**, PowerShell | Use `.\gradlew`, **not** `./gradlew`. Use `;` not `&&` for command chaining, or run commands separately. |
| **Android Studio** with a bundled JBR (likely JDK 21) | Project targets **JVM 17**. Use Gradle toolchains (`kotlin { jvmToolchain(17) }`), don't fight the IDE's JDK. |
| **Physical device**, Android 16 (API 36), USB debugging | No emulator locally. `adb devices` must show it before any `connected*` task. |
| **Git Bash is required** for `scripts/guard-*.sh` | They're bash. **But bare `bash` in PowerShell resolves to `C:\Windows\System32\bash.exe`, which is WSL, not Git Bash** — a different Linux with a different `git` and a different filesystem view. Call Git Bash explicitly (`D:\Software\Git\bin\bash.exe`) or put its `bin` ahead of System32 on PATH. See `CLAUDE.md §11`. |
| **No `make`** | Never write a Makefile or reference `make`. Gradle tasks only. |
| **NTFS is case-insensitive** | Two files differing only by case will pass locally and break Linux CI. The `guards` job catches it; don't create the situation. |
| **260-char path limit** | Deep module paths + Room generated sources will hit it. Long paths must be enabled (Step 0). |

**Never run `adb uninstall`.** Ever. It destroys test data and masks BUG1. Use `installSmsFullDebug` — install-over-install is a feature we are continuously testing.

---

## STEP 0 — ENVIRONMENT AUDIT (do this first, change nothing)

Run these, report the output as a table, and **stop**:

```powershell
java -version
git --version
git config --get core.longpaths
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
where.exe bash
$env:ANDROID_HOME
```

Then tell me:
1. Anything missing or misconfigured, with the exact fix command.
2. Whether long paths and `core.autocrlf=false` need setting.
3. Whether Windows Defender exclusions for the project dir are worth adding (they are — build times roughly halve).

**Do not proceed until I confirm.**

---

## STEP 1 — BLOCKING DECISIONS (ADRs)

Three decisions gate Phase 0. For each, write `docs/adr/NNNN-*.md` with context, options, trade-offs, and **your recommendation with reasoning** — then stop and let me choose.

**ADR-0002 — DEBIT/CREDIT table strategy.** Separate `debit_entry` / `credit_entry` tables, versus one `ledger_entry` table partitioned by a mandatory `ledger` column. Law 2 says the ledgers must never interact. Cost out both against: schema duplication, query ergonomics, how the isolation invariant is *mechanically enforced* rather than merely intended, and migration complexity. This is the highest-leverage schema decision in the project — argue it properly.

**ADR-0010 — Crypto library selection.** *(Was written here as "ADR-0003". Corrected: ADR-0003 is Accepted in `SPEC.md §14` and stays that way — the key-hierarchy design is settled. The library choice is a separate decision and gets its own record.)* Open: Argon2id implementation on Android, BIP-39 wordlist source, HKDF provider, and whether to use Tink or hand-roll the AES-GCM wrapping. Flag anything with a native dependency that could complicate the 15 MB budget. Include the recommendation on whether KEK-C (Argon2id) is worth its native dependency at all — `SPEC.md §7.2` currently defers it to P1.

**ADR-0009 (new) — SQLCipher key rotation.** Not currently in the spec, and it should be. If the DEK is ever compromised or the user wants to rotate their recovery phrase, what's the procedure? `PRAGMA rekey` is not atomic across a crash. Propose a safe design (likely: backup → new DB with new key → verify → swap → delete old). Add it to the spec once decided.

**Stop after writing all three. Do not implement.**

---

## STEP 2 — BUILD SKELETON (no features)

Once ADRs are approved:

1. Gradle wrapper, `settings.gradle.kts`, `gradle.properties` (tuned for a Windows dev box: `-Xmx4g`, parallel, caching, configuration cache).
2. `gradle/libs.versions.toml` — **every** version lives here. No hardcoded versions, no dynamic versions (`+`, `latest.release`). Build reproducibility is a hard requirement.
3. `build-logic/` convention plugins: `ledgerflow.android.library`, `.compose`, `.hilt`, `.room`, `.feature`, `.test`.
4. `version.properties` with `versionCode=1`, plus the Gradle task that increments it.
5. Empty but wired modules per `CLAUDE.md §3`, including the `smsFull` / `playSafe` product flavours and the `:feature:ingest` source-set split. **The flavour split lands now, not at P5** — retrofitting it into a coupled codebase is exactly the pain we're avoiding.
6. `git update-index --chmod=+x gradlew` and commit.

**Verify:** `.\gradlew assembleSmsFullDebug assemblePlaySafeDebug` both succeed on an empty app. Report build times.

**Stop and show me the module graph and `libs.versions.toml` before moving on.**

---

## STEP 3 — CRYPTO CORE (`:core:crypto`)

This is a `CLAUDE.md §7` danger zone. Build it before the database, because the database depends on it.

Implement per `SPEC.md §7.2`: DEK generation, KEK-A (Keystore, `userAuthenticationRequired = false`, StrongBox with graceful fallback), KEK-B (BIP-39 phrase → HKDF, derivation pinned byte-for-byte with committed golden vectors), the wrapped-blob files, the canary check, and the self-healing unlock flow from `§7.3`.

**KEK-C (Argon2id passphrase) is deferred to P1** — see `SPEC.md §7.2`. P0 reserves the `wrapped_dek_pass.bin` slot and accounts for it in `app_meta.dekWrapVersion`, but ships no Argon2id. It is the only piece of the hierarchy that risks a native `.so` against the APK budget, and its entire value is the narrow "Keystore died *and* I'd rather not type 24 words" case that KEK-A and KEK-B already cover between them.

**Non-negotiables — restate these back to me before you start, so I know you've internalized them:**
- Backups are encrypted with KEK-B **only**. Never KEK-C.
- Decryption failure routes to a Recovery screen. It never wipes, never prompts to wipe.
- BIP-39 checksum validates *before* the expensive KDF runs, or a typo looks like a hang.
- No `setUserAuthenticationRequired(true)` on the DEK-wrapping key.

**Tests before UI:** unit tests for wrap/unwrap round-trips on all three KEKs; an instrumented test that simulates Keystore invalidation and asserts phrase recovery re-wraps successfully with zero data loss.

**Stop for review.** I want to read this module line by line.

---

## STEP 4 — DATABASE (`:core:database`)

1. Room + SQLCipher via `SupportOpenHelperFactory`, keyed from `:core:crypto`.
2. Schema v1 implementing `SPEC.md §6.1` — but **only the tables Phase 0 needs**: `app_meta`, `category`, `merchant`, `payment_method`, and the ledger tables per ADR-0002. Skip `pending_transaction`, `sms_raw`, `notification_raw`, `daily_rollup` — those arrive with their features. An unused table is a migration liability. *(`category_group` was listed here originally and has been removed: it is analytics-only, it lands at P3, and the list omitted `category_group_member` without which it does nothing — it was exactly the migration liability the sentence warns about.)*
3. `room.schemaLocation` configured, v1 JSON **committed**.
4. Migration test harness with `MigrationTestHelper`, plus a deliberate throwaway v1→v2 migration to prove the harness works, then revert it.
5. WAL checkpoint on `ON_STOP`.
6. Type converters, including `Money ↔ Long`.

**Verify:** `bash scripts/guard-schema.sh` passes. Migration test runs green on the device.

---

## STEP 5 — BACKUP / RESTORE (the Phase 0 exit gate)

Implement the `.lfbk` container from `SPEC.md §5.9` and the atomic write from `§7`: write `.tmp` → fsync → **decrypt-and-parse to verify** → rename. A backup that hasn't been round-trip verified is not a backup, and `lastBackupAt` must not be updated for it.

**The gate:** an instrumented test that seeds every table with fixture data, backs up, wipes the DB *and* the Keystore key, restores using only the 24-word phrase, and asserts **row-level equality across every table**. Not row counts — actual content.

This test is the reason Phase 0 exists. If it can't be made to pass, we have an architecture problem, not a bug. Tell me immediately if you hit one.

---

## STEP 6 — ONBOARDING + DESIGN SYSTEM FOUNDATION

1. `:core:designsystem`: color tokens (`SPEC.md §9.1`), typography with **tabular figures** for amounts, motion tokens, `LfTheme`. Atoms: `LfScaffold`, `LfButton`, `LfCard`, `LfTextField`. That's it — the rest arrive with the features that need them.
2. `:feature:onboarding` per `SPEC.md §7.4`: base currency → recovery phrase display → word challenge (3 random positions, **no skip button**) → Recovery Kit save via SAF → backup location grant → optional passphrase.
3. `enableEdgeToEdge()` + `WindowInsets.safeDrawing` from the very first screen. Retrofitting insets is BUG5.
4. Previews on every screen: `@PreviewScreenSizes`, `@PreviewFontScale`, `@PreviewLightDark`.

---

## RULES OF ENGAGEMENT

- **Commit incrementally**, Conventional Commits, one logical change per commit. Don't hand me a 60-file monolith.
- **Tests alongside code**, not after. A step isn't done when it compiles.
- **Propose, never unilaterally adopt, a new dependency.** Justify size, maintenance status, and why the platform SDK isn't enough. Anything with a native `.so` gets extra scrutiny — the APK budget is 15 MB.
- **Ask when the spec is ambiguous.** Add genuine gaps to `SPEC.md §16` rather than inventing an answer.
- **If a request would violate one of the Seven Laws, say so loudly.** Don't quietly work around them.
- **Flag over-engineering, including mine.** If a spec requirement is more machinery than the problem warrants, argue the case. I'd rather cut it now.
- After each step, run the verification commands and report actual output. Don't claim green without showing it.

---

## FIRST RESPONSE

Do exactly this, nothing more:

1. Confirm you've read `SPEC.md` and `CLAUDE.md` in full.
2. Restate the Seven Laws in your own words — compressed, not copy-pasted. I'm checking comprehension, not recall.
3. Run the Step 0 environment audit and report the table.
4. List anything in the spec you think is wrong, contradictory, over-engineered, or under-specified. **Be blunt.** This is the cheapest moment in the project to find out.
5. Stop.
