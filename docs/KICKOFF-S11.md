# KICKOFF S11 — `TransactionIngestSource` abstraction + flavour skeleton

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the session log for what just shipped, plus the
scope for S11 only.

---

## 1. Session log — S9 and S10, as they actually happened

The 13-step P1 plan lives in `docs/KICKOFF-S7-S8.md` §"The 13-step P1 plan, and
where we are". Two sessions landed since that table was last accurate, and they
did not land in the order the table implies — worth recording plainly rather
than letting the commit history be the only account.

**S9 was scoped as "the Ledger list" and shipped that** (`96ccac8`) — Paging 3
read path, windowed to 30 days, grouped into recency bands, unsaved drafts
surfaced above them, soft delete + the bin (restore/erase, both books in one
list per ADR-0015). `docs/adr/0014` (Paging reaches the domain interface) and
`docs/adr/0015` (the bin lists both books) came out of this step.

**A second commit followed in the same sitting that was not S10 from the plan.**
While reviewing the shipped Ledger/Bin work, hiding a merchant was found to have
no restore path anywhere in the app — a gap the S9 kickoff never scoped and the
plan table does not mention. That became `7825c11`
(`feat(taxonomy): hidden lists, restore, and a guarded erase for the taxonomy`):
Hide/Restore/Erase for categories, merchants and payment methods, a purge
behind a reassign-or-block rule enforced in code (`ledger_entry.merchant_id` is
`ON DELETE SET NULL` and `category_id` carries no key at all, so the schema
will not catch a bad purge), plus two real bugs found and fixed along the way —
BUG11 (a hidden merchant's name could not be reused, and using it threw) and
BUG12 (deleting a category orphaned its subcategories instead of hiding them
with it). `docs/adr/0016` records the shape. **This was off-plan** — flagged as
such when it happened, and recorded here so the plan table below is honest
about what actually consumed the session rather than only what was scheduled.

**S10 — `:feature:export`, CSV via SAF — is what the plan actually called for
next, and it shipped after the taxonomy detour** (`4f939d8`, plus two follow-up
style commits `57ae275` and `fdbf5dc` narrowing the screen's copy down to a
single card with two chips). `docs/adr/0017` settles the shape: money and
timestamps written twice (the schema's integer verbatim, plus a rendered form —
the decimal assembled by integer arithmetic only, Law 3), `ledger_entry` split
into two files per book (§5.5's "separate lists" promise, not a Law 2
requirement — a CSV derives no figures), soft-deleted rows included with their
`deleted_at`, and the file list driven by `DatabaseBackupManager.export()`
(made `public`) rather than a second table enumeration that could silently
drift from the first.

### Where the 13-step table actually stands now

| # | Step | Status |
|---|---|---|
| S0–S6 | Convention plugin/Hilt, unlock flow, SAF Recovery Kit, app shell, taxonomy layer, schema v2, `:feature:categories` | ✅ |
| S7 | `:core:domain` ledger layer — `ApproveTransactionUseCase`, draft repository | ✅ |
| S8 | `:feature:entry` — manual entry, draft persistence, BUG6 test | ✅ |
| S9 | `:feature:ledger` — Paging 3, the bin | ✅ (`96ccac8`) |
| — | *Off-plan: taxonomy Hide/Restore/Erase, BUG11, BUG12* | ✅, unscheduled (`7825c11`, ADR-0016) |
| **S10** | **`:feature:export` — CSV via SAF** | ✅ (`4f939d8`, `57ae275`, `fdbf5dc`, ADR-0017) |
| **S11** | **`:feature:ingest` — `TransactionIngestSource` abstraction + flavour skeleton only** | ✅ (`756398b`) |
| — | *Off-plan, owner-directed: itemised entries (ADR-0018) and the three defects it exposed* | ✅, unscheduled (`1716832`, `16db110`, `ff43609`, `da65982`, `ea0b1ae`) |
| **S12** | **`TESTING.md` + carryover (bundled font, onboarding CTA pinning)** | ✅ (`3e6ad5b`, `76f940b`, `d21329f`) |
| P2-1 … P2-3 | Ingest: schema v6/v7, capture live, rule engine + golden corpus | ✅ — **see `docs/KICKOFF-P2-4.md`**, which logs that session and scopes the next |

### Verified state as of `fdbf5dc`

- `preMergeCheck` green on **both** flavours (last full run: this session, after
  the CSV-card style commit).
- New this session: taxonomy — 7 + 17 + 5 + 6 = **35 tests** across
  `TaxonomySingleWriterTest`, `TaxonomyPurgeTest`, `Bug11_...Test`,
  `Bug12_...Test`. Export — 13 + 10 + 5 + 8 + 9 = **45 tests** across
  `CsvWriterTest`, `CsvMoneyTest`, `ExportCoversEveryTableTest`,
  `CsvExportRoundTripTest`, `ExportViewModelTest`.
- `:core:data` instrumented suite: **153 tests, 0 failures** on SM-S721B
  (checked mid-session, before the two copy-only style commits — neither
  touched test sources).
- `guard-schema.sh` and `guard-version.sh` pass. **No schema change in either
  commit** — S9's taxonomy lifecycle and S10's export both read the existing
  v5 schema; still v5.
- Device font scale is back at its documented **1.15** baseline (it drifted to
  1.0 mid-session during on-device verification and was restored both times).

---

## 2. What already exists for S11

| Piece | State |
|---|---|
| `LedgerFlowFlavor` enum + `FLAVOR_DIMENSION` (`build-logic/convention/.../AndroidConventions.kt`) | **Done, since Phase 0.** `smsFull` / `playSafe` are wired centrally in the library convention plugin — every module already builds both variants. This is *why* `preMergeCheck` has always run both flavours; S11 does not need to invent flavour wiring. |
| `:feature:ingest` module | `build.gradle.kts` only — a bare `ledgerflow.android.feature` module with a namespace. **Zero source files, zero source-set directories.** |
| The three-source-set layout CLAUDE.md §3 describes | Not yet created: `feature/ingest/src/main/` (rule engine, dedupe, worker, `adapters/NotificationAdapter`), `feature/ingest/src/smsFull/` (`adapters/SmsAdapter` + `RECEIVE_SMS` manifest entry), `feature/ingest/src/playSafe/` (no-op SMS adapter) |
| `RawIngestEvent`, `TransactionIngestSource` | Not yet defined anywhere. §3.1 gives the shape: `RawIngestEvent(sourceType, sender, body, receivedAt, packageName?)` |
| `pending_transaction` table | **Does not exist.** Schema is v5; this table is explicitly P2 per §16 Q7 (`pending_line_item` is still elided). S11 is *architecture only* — the abstraction and flavour skeleton, not the parser or the review UI. |
| Notification allowlist, rule engine, dedupe | P2, not S11. Don't build ahead of the plan the way the taxonomy detour did. |

---

## 3. Scope

Per `SPEC.md` §3.1 and the P1 row: **the abstraction and a compiling,
installable skeleton for both flavours. Not ingestion.**

1. **`:core:domain`** (or a new `:core:ingest` if the abstraction turns out to
   need types neither `:core:domain` nor `:core:model` should own — ask before
   creating a module, per CLAUDE.md §10) — `TransactionIngestSource` interface
   and `RawIngestEvent`, exactly as §3.1 specifies. This is the one piece that
   is genuinely shared and must be source-agnostic: **no `if (source == SMS)`
   outside an adapter package** (CLAUDE.md §0, and it is in the "What NOT To
   Do" table for a reason).
2. **`:feature:ingest/src/main`** — wherever the two adapters land, plus
   whatever skeletal worker/service scaffolding S11 actually needs to prove the
   abstraction compiles and installs. Read CLAUDE.md §7 "`:feature:ingest`"
   danger-zone notes before writing the SMS receiver stub: **~10 seconds before
   the system kills it, no parsing, no network, no DB joins — write raw and
   enqueue a Worker.** Even a skeleton receiver should not violate that shape,
   because the real one will be built on top of it rather than rewritten.
3. **`:feature:ingest/src/smsFull`** — the `SmsAdapter` stub + `RECEIVE_SMS` in
   this flavour's manifest only.
4. **`:feature:ingest/src/playSafe`** — a no-op SMS adapter, so the shared code
   compiles against the interface without the restricted permission ever
   appearing in this flavour's manifest.
5. **Wire into `:app`** only as far as "both flavours compile and install" —
   this is explicitly not a UI step; there is no Inbox, no review screen, no
   `pending_transaction` yet.

**Out of scope for S11** (do not drift into these, the way the taxonomy work
drifted past S9's scope): the rule engine, cross-source dedupe, the
notification allowlist filter, `pending_transaction`/`pending_line_item`
schema, the Inbox review UI, actual SMS or notification parsing. All P2.

---

## 4. Decisions to make — do not invent silently

**a. Does `TransactionIngestSource` live in `:core:domain` or a new module?**
`:core:domain` currently depends on `:core:model` + `:core:common` +
`paging-common` only (ADR-0014's carve-out, which that ADR is explicit is "not
a precedent" for a second AndroidX coordinate). If the interface needs nothing
Android-shaped, it belongs in `:core:domain` beside the other repository ports.
If it needs something a `NotificationListenerService` or `BroadcastReceiver`
signature forces on it, that is the trigger to ask rather than to widen
`:core:domain`'s dependency surface quietly.

**b. What, precisely, counts as "flavour skeleton" for this step.** The P1 row
says "both compiling, both installable" — it does not say both *doing
anything*. Confirm with the owner before writing a real
`NotificationListenerService` implementation or a real `RECEIVE_SMS` receiver
body: S11 may be satisfied by adapters that implement the interface and write
nothing, same as `playSafe`'s SMS side does permanently.

---

## 5. Non-negotiables for this specific work

- **Source-agnostic downstream, without exception.** CLAUDE.md §0: "Everything
  downstream of a capture adapter is source-agnostic. If you find yourself
  writing `if (source == SMS)` outside `:feature:ingest`'s adapter package,
  you've broken the abstraction." This is the one rule S11 exists to establish
  correctly before P2 builds on top of it.
- **Both flavours build and test on every PR** — already true via the Phase 0
  convention plugin; S11 must not break it. `playSafe` is not a "later"
  deliverable (CLAUDE.md §9 table).
- **No `INTERNET` permission**, in either flavour, ever (Law 6).
- **`RECEIVE_SMS` appears in exactly one flavour's manifest** — `smsFull`'s.
  If it shows up in a shared manifest or in `playSafe`, that is the whole point
  of D-04 broken in one line.
- If S11 turns up a genuine spec gap (and the P1 table's ordering slip this
  session suggests it might), **add it to `SPEC.md` §16 Open Questions** rather
  than deciding it and moving on, the way the taxonomy work should have been
  raised as a question before it became a commit.

---

## 6. Definition of done

CLAUDE.md §12 in full, plus specifically:

- [ ] `TransactionIngestSource` + `RawIngestEvent` exist, are source-agnostic,
      and compile against both flavour adapters
- [ ] `RECEIVE_SMS` is in `smsFull`'s manifest only — grep for it across both
      flavour source sets as a final check
- [ ] `.\gradlew preMergeCheck` green on **both** flavours
- [ ] `.\gradlew assembleSmsFullDebug assemblePlaySafeDebug` — both install on
      the device without error (`installSmsFullDebug`, then repeat for
      `playSafe` if the owner wants both on-device at once — check first,
      they may share an install slot differently than expected)
- [ ] No schema change — S11 is architecture only, `pending_transaction` is P2.
      If this step finds itself writing a `Migration`, stop and re-read scope.
- [ ] `SPEC.md` updated if behaviour diverged from §3.1
- [ ] ADR written for the decision in §4a if it goes anywhere other than
      `:core:domain`

---

## 7. Environment notes that cost time in recent sessions

- Gradle needs `JAVA_HOME` set every session:
  `$env:JAVA_HOME = "D:\Software\Android App development\jbr"`. Not set in the
  shell by default; `bash` cannot see it at all, and `java`/`gradlew` alone
  fails with "JAVA_HOME is not set and no 'java' command could be found".
- `adb` is not on PATH:
  `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Confirm
  the device with `& $adb devices -l` before any `connected*` task.
- **Never `adb uninstall`.** Use `installSmsFullDebug` (install-over-install).
  `connectedAndroidTest` uninstalls both APKs when it finishes and takes the
  vault with it — `gradle.properties`'
  `android.injected.androidTest.leaveApksInstalledAfterRun=true` stops that;
  do not remove the line.
- **Device font scale is the user's, currently 1.15.** It drifted twice this
  session during on-device font-scale-2.0 checks and had to be restored both
  times. Set it back immediately after the check that needed it, in the same
  breath, rather than at the end of the session.
- Bash heredocs have mangled multi-line edits in this environment repeatedly.
  Write a Python script to the scratchpad directory and run it, rather than
  inlining a large heredoc.
- `bash` on this box may resolve to the WSL launcher rather than Git Bash —
  verify with `(Get-Command bash).Source` before running `scripts/guard-*.sh`;
  if it points at System32/WindowsApps, call
  `D:\Software\Git\bin\bash.exe scripts/guard-schema.sh` explicitly.
- `uiautomator dump` + `dumpsys window` (for the nav-bar `mFrame`) is how
  layout claims get checked on this device, screen 1080x2340. RTL was verified
  this session via `adb shell cmd locale set-app-locales <pkg> --locales ar`,
  which is safe to use for a single app without touching system-wide RTL
  settings — reset it with the same command and an empty `--locales` after.

---

## 7a. Session log — S11 as it actually happened

Recorded here rather than in a new kickoff file, because §1 above is where the
next reader will look for what the last session did.

**S11 itself landed as scoped** (`756398b`): `TransactionIngestSource` and
`RawIngestEvent` in `:core:domain` (§4a decided that way — nothing about the
shape is Android-typed, so ADR-0014's carve-out is untouched and no ADR was
needed), both capture components declared and inert, `playSafe` binding a real
SMS adapter that reports `UNSUPPORTED_IN_BUILD` forever. The owner chose the
"components declared, inert sink" depth for §4b. 19 tests.

Two things worth carrying forward:

- **`restrictedPermissionCheck`** was added to the root build and mirrored in
  `ci.yml`. The DoD asked for a one-time grep; D-04's failure mode is a single
  misplaced line that produces no build failure and no on-device symptom, so it
  became a durable guard instead. Verified by planting violations.
- **`super.onReceive` does not compile in Kotlin.** Hilt's docs show it;
  `BroadcastReceiver.onReceive` is abstract, so the call is rejected. The
  generated base carries `@OnReceiveBytecodeInjectionMarker` and the Hilt Gradle
  plugin inserts it into the bytecode. Confirmed by disassembling the
  transformed class. The receiver therefore has no visible super call and is
  correct anyway — that is written into the file so nobody "fixes" it.

**Then the session went off-plan, at the owner's direction**, and it is worth
being plain that this was requested rather than drifted into: itemised entries
(ADR-0018) — an entry that files at line grain and stores no category of its
own. The feature is in `1716832`; the shared editor lives in `:core:ui`, which
was an empty module until now, so `:feature:inbox` can drive the same component
at P2 without one feature depending on another.

**It exposed three defects, each found by using the app rather than by a test:**

1. The Ledger list rendered an itemised entry as "Unfiled" — it reads
   `ledger_entry.category_id`, which such an entry deliberately leaves null
   (`ff43609`).
2. The bin did the same, through its *own* statement. Fixing the list and
   finding the bin still broken is the lesson: those are two queries, and the
   bin must read `ledger_entry` directly because the views hide deleted rows.
3. **`fix(taxonomy)` (`da65982`) is the one to read.** The category purge
   counted only entry-grain references, so a category used by nothing but line
   items counted 0, the reassign-or-block rule never fired, and erasing it
   silently orphaned every line. `line_item.category_id` has no foreign key, so
   nothing downstream would have complained. Irreversible.

**`LedgerIsolationTest`'s aggregate rule was sharpened** (recorded as an
amendment under ADR-0002's Consequences, per that ADR's own instruction to
reopen rather than exempt). The old rule — "any literal containing `SUM(` must
mention a view" — was a string proxy: it rejected a per-entry aggregate that
cannot net books, and passed two that were never actually checked. Both new
assertions were verified against planted violations.

Also this session: a merchant can now be added from the entry form itself
(`ea0b1ae`), and §5.1/§5.2/§5.5 record that ingest resolves a parsed
`merchantRaw` through `createOrGet` and **may never fail for a merchant that
does not exist yet**. That half is a spec rule, not code — there is no parser to
call it.

**Owner instruction, standing:** do not build or install the `playSafe` flavour
for device testing until Play distribution is actually on the table. This does
**not** relax `preMergeCheck`, which still builds and tests both flavours,
because that is CI parity rather than a personal habit — raise it explicitly if
that should change too.

---

## 8. A process note, for whoever kicks off S11

The taxonomy work in S9's sitting was good work, verified on device, fully
tested — and it was still a scope violation the owner had to catch and correct
before S10 could start. `CLAUDE.md` §10 already says "ask before assuming" on
anything touching data lifecycle; the miss here was starting to *build* off a
self-identified gap instead of raising it as a question and waiting. If S11
turns up a similarly real gap elsewhere in the app, the move is the one CLAUDE.md
already prescribes: flag it, propose it, wait for a nod — not build it in the
same breath as the thing that surfaced it.
