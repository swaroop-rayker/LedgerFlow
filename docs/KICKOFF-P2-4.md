# KICKOFF P2-4 — `pending_transaction`, the approval queue Law 1 is about

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the session log for what just shipped, plus the
scope for P2-4 only.

The owner refers to the P2 steps as **S1…S9**; this file's P2-4 is their "S4".

---

## 1. Session log — S12 and P2-1 … P2-3, as they actually happened

Eleven commits on `s12-testing-matrix-font-cta`, branched from `2c61828`.
**Never pushed; no PR.** 130 files, ~10.5k insertions.

### S12 — `TESTING.md` + carryover (3 commits)

- `3e6ad5b` **`TESTING.md`**, the pre-release manual matrix. Written as the full
  product matrix with every row tagged by the phase that activates it, rather
  than only the rows P1 can run. Rows needing the 24 words are marked
  **[owner]**; `playSafe` device rows are marked blocked-by-choice against the
  standing instruction rather than dropped.
- `76f940b` **Inter bundled** (`InterVariable.ttf` 4.1, OFL 1.1). Two silent
  failures found by measuring on the device, both recorded in `LfFonts.kt`:
  handing `variationSettings` to a Compose `Font(resId=…)` yields metrics that
  match no real master, and the device's **"Bold text" accessibility setting**
  (`font_weight_adjustment = 300`) collapses a three-weight family to one. Six
  weights are registered for that reason. Licence ships in `assets/`, not
  `res/raw/`, because release shrinking would strip it.
- `d21329f` **Onboarding CTAs pinned.** Measured at font scale 2.0, **three of
  the five gate steps had their action off screen** — including the first, where
  the currency list alone pushes "Continue" past the fold. Onboarding is a gate,
  so that was a user who could not enter the app.

### P2-1 … P2-3 — automated ingest (5 commits)

- `d8f7ad0` **Schema v6** — `sms_raw`, `notification_raw`, `package_allowlist`,
  `sender_allowlist`, `parser_rule`, `pending_transaction`. Purely additive.
  Four §16 questions closed as **D-09/D-10/D-11**; **Q7 (`pending_line_item`)
  deferred to P4** with a test asserting its absence.
- `17e8ea0` **The pre-migration snapshot** (ADR-0019). See §2.
- `57b5230` **P2-2, capture goes live.** The S11 seam paid off exactly as
  designed: the sink was the only class that changed and neither adapter was
  touched. Introduced WorkManager.
- `b3f8ed4` **SMS driven by real PDUs**, plus BUG6's test fixed. See §3.
- `f96e116` **P2-3, the rule engine**, the shipped ruleset and the golden
  corpus. Schema **v7** adds `parser_rule.instrument_hint`.
- `fb13677`, `7a1f968` **Two real bank SMS from the owner's phone**, and what
  they broke. See §4.

---

## 2. ADR-0019 — the pre-migration snapshot could not be what §8.1 said

`CLAUDE.md` §7 has asserted since Phase 0 that "a pre-migration `.lfbk` backup is
written and verified before any migration runs". **It never existed and could
not.** `DatabaseBackupManager.writeBackup` takes the BIP-39 seed, ADR-0011 means
the app never holds the phrase at launch, and the function had no production
caller — only tests. §8.1 asked for exactly what §7 forbids one paragraph away
in its note on the purge dialog.

Resolved by owner decision: **the snapshot is a byte copy of the encrypted
database file.** No phrase, no new key material, no third wrap. A backup must be
portable and survive the device dying; a rollback snapshot must survive the next
sixty seconds on this device. §8.1 is amended in two places (the byte-progress
requirement, and the retention line).

Verified twice on the owner's real vault: v5→v6 and v6→v7 both took a snapshot,
migrated, opened with no Recovery screen, and discarded the snapshot on the next
clean launch.

---

## 3. Three guards that reported success while not running

This is the most important thing to carry forward. **The same shape bit three
times in one session**, and each instance was silent:

1. **`TaxonomySingleWriterTest` had been failing since `da65982` (S11).** Its
   assertion was a string proxy that ADR-0018's SQL rewrite invalidated — and
   nobody saw it, because the test reads the repository at run time and Gradle
   could not see that as a task input, so the task stayed UP-TO-DATE for three
   sessions. Fixed in `f340eec`: the assertion is on the property now, and both
   repo-scanning modules declare the sources as inputs.
2. **`Bug6_DraftSurvivesProcessDeathTest` had not compiled since ADR-0018.** One
   of the Seven Laws' named tests, not running for two sessions, because
   **`preMergeCheck` never compiled androidTest sources**. It now does, for both
   flavours. That change immediately surfaced two more build-config faults, both
   fixed.
3. **`GoldenCorpusTest` was not re-running.** Adding the owner's first real bank
   SMS — which the ruleset could not parse — produced a **green build**. The
   corpus is the parser's specification. Fixed in `fb13677`.

**If you add a test that reads files from the repository at run time, declare
them as task inputs in that module's `build.gradle.kts`.** Three precedents now
exist; copy one.

---

## 4. What the two real bank SMS taught us

The owner supplied two real HDFC messages. **Both broke the ruleset, and the
second broke it in the worse way.**

- The **debit** (`Sent Rs.788.00 / From HDFC Bank A/C *NNNN / To MERCHANT / On
  dd/MM/yy / Ref …`) matched **nothing at all** — not the UPI rules, which
  expected "debited", and not even the generic fallback, whose verb list had no
  "Sent". Forced `DOT_MATCHES_ALL` in the engine: real bank SMS are multi-line.
- The **credit** (`Credit Alert! / Rs.1.00 credited to HDFC Bank A/c XXNNNN on
  dd-MM-yy from VPA someone@handle (UPI NNNN)`) **fell through to the generic
  fallback**, producing an amount and a direction while silently losing the
  payer, the account, the date and the reference. A miss is visible; a shallow
  match looks like success. The corpus asserts which *rule* wins for exactly
  this reason — without that assertion it would have passed.

Both share one thing no synthetic fixture had: **the bank's name sits between
the preposition and the account** (`credited to HDFC Bank A/c …`). Two
independent real formats, the same wrong assumption in every invented rule.

**Treat every remaining synthetic rule as probably wrong in the same way.** The
card, ATM, NEFT/IMPS rules and *all seven* notification cases have no real
coverage.

---

## 5. Where the plan stands

| # | Step | Status |
|---|---|---|
| S0–S11 | Phase 0 + P1, through the `TransactionIngestSource` skeleton | ✅ |
| **S12** | `TESTING.md` + carryover (bundled font, onboarding CTA pinning) | ✅ this session |
| — | *Off-plan, owner-approved: ADR-0019 pre-migration snapshot* | ✅ `17e8ea0` |
| **P2-1** | Schema v6 — the ingest tables | ✅ `d8f7ad0` |
| **P2-2** | Capture goes live (persisting sink, allowlist filter, worker) | ✅ `57b5230` |
| **P2-3** | Rule engine + shipped ruleset + golden corpus | ✅ `f96e116` |
| **P2-4** | **`ParseIngestWorker` → `pending_transaction`** | ⬅ **do this** |
| P2-5 | Cross-source dedupe (±3 min key, `DUPLICATE_SUPPRESSED`) | pending |
| P2-6 | `:feature:inbox` — list, filters, review, approve/discard | pending |
| P2-7 | Notifications — `inbox_high`, deep link, actions, grouping | pending |
| P2-8 | Notification-listener permission UX, rebind, health banner | pending |
| P2-9 | Exit criteria — 50+50 corpus, named dedupe test | pending |

### Verified state as of `7a1f968`

- `preMergeCheck` green on **both** flavours. It now also **compiles**
  instrumented sources.
- Instrumented on SM-S721B: crypto 5, database **72**, data **186**,
  designsystem 10, ingest 5, onboarding 6, app 2 — **282, zero failures**.
- `:feature:ingest` unit: 32, including the golden corpus (25 fixtures) and
  `MoneyTextTest`.
- Both guard scripts pass. `guard-schema.sh` sees v6 **and** v7 and validates a
  migration + test for each.
- Schema is **v7**. The owner's device is on v7, migrated through the snapshot
  guard.
- Device settings untouched all session: font scale **1.15**,
  `font_weight_adjustment` **300**, no app locale override.

---

## 6. What already exists for P2-4

| Piece | State |
|---|---|
| `pending_transaction` table | **Exists, empty, nothing writes to it.** Schema v6. Indices on `(status, created_at)`, `(dedupe_key, created_at)`, `(suppressed_by_id)`. No foreign keys — deliberately, see the entity KDoc. |
| `ExtractedTransaction` | `:core:domain/ingest`. Every field nullable but `direction` and `confidence`. Has `isReviewable`. |
| `ParserRuleEngine` | `:feature:ingest/parser`. Returns `Matched(ruleId, extracted)` or `Unmatched`. Pure Kotlin. |
| `ParseCapturedMessages` | `:feature:ingest/pipeline`. Runs the engine over `capturedEvents()` and records `matched_rule_id` + `PARSED`/`UNMATCHED` on the raw row. **This is the class P2-4 extends.** |
| `RawIngestRepository` | `:core:domain/ingest`. Has `capturedEvents`, `recordParseOutcome`, `parserRules`, the allowlists, retention. **No pending-row method yet.** |
| `PendingStatus` | `:core:model`. `PENDING` / `APPROVED` / `DISCARDED` / `FAILED`, exactly §6.1's four. "Suppressed" is deliberately *not* a status — it is `suppressed_by_id`. |
| `MerchantRepository.createOrGet` | Exists and is what §5.1 requires ingest to call. **Not yet called from ingest.** |
| Inbox | `:feature:inbox` module exists and is empty. P2-6. |

---

## 7. Scope for P2-4

**Write `pending_transaction` rows, and nothing beyond that.**

1. **A pending row for every message the pipeline resolves.** `ParseCapturedMessages`
   currently records a verdict on the raw row and stops. It must now also create
   the candidate.
2. **§5.1's never-drop rule, implemented.** An `Unmatched` message *from an
   allowlisted sender* still becomes a `PENDING` row with `confidence = 0` and
   `needs_manual_fill = 1`. A `SENDER_NOT_ALLOWLISTED` row must **not**.
3. **Serialising `ExtractedTransaction` into `extracted_json`**, versioned. The
   `draft_entry.payload_json` precedent applies: the repository treats it as
   opaque. But note the difference — `:feature:inbox` must read it at P2-6 and
   features may not depend on features, so **the payload type stays in
   `:core:domain` and the encoding lives in `:core:data`**.
4. **`dedupe_key` computed and stored** per §3.1: `(amountMinor, direction,
   roundToMinute(occurredAt), accountLast4 ?: merchantNormalized)`. Computing and
   storing it is P2-4; *acting* on collisions is P2-5.
5. **Idempotency.** The worker re-runs. A raw row that already produced a pending
   row must not produce a second — `raw_ref_id` is the link.

**Out of scope, do not drift into these:** cross-source dedupe suppression
(P2-5), the Inbox UI (P2-6), notifications (P2-7), the listener permission flow
(P2-8), `pending_line_item` (P4, §16 Q7), and the Settings → "SMS Parsing" rule
editor with its test bench (§5.1 names it; the owner has not objected to
deferring it to P5 — raise it again rather than building it).

---

## 8. Decisions to make — do not invent silently

**a. When does the merchant get resolved?** §5.1 is emphatic that ingest resolves
`merchantRaw` through `MerchantRepository.createOrGet` and **may never fail for a
merchant that does not exist yet**. What it does not say is *when*: at parse time
(so `pending_transaction` holds a `merchant_id`) or at approval (so the pending
row holds only `merchantRaw`). Creating merchants for candidates the user later
discards would litter the taxonomy; not creating them moves the work to approval.
Ask.

**b. What is `occurredAt` when the message states no date?** The extraction can
be null. Falling back to capture time is defensible and wrong by up to a day;
leaving it null pushes the decision to the review screen. §5.1 lists
`transactionAt` as an extraction target and says nothing about the fallback.

**c. Does a `FAILED` status ever get written?** §6.1 lists it on
`pending_transaction`, but the current pipeline has no path that produces it —
`Unmatched` is a *result*, not a failure. Either wire it to something real (a
worker exception mid-row) or leave it unwritten and say so.

**d. Should `SENDER_NOT_ALLOWLISTED` bodies be cleared sooner than 90 days?**
Raised twice, deferred twice by the owner — currently spec-literal (kept, marked,
cleared at 90 days by D-09). Do not change it without asking.

---

## 9. Non-negotiables for this specific work

- **Law 1 is this step's subject.** Only `ApproveTransactionUseCase` may insert
  into `ledger_entry`. `LedgerSingleWriterTest` guards every door into that
  table; P2-4 must not open a fifth. A pending row is *not* a ledger row and
  must appear in no total, no ledger query and no rollup.
- **Never silently drop a financial SMS** (§5.1). The `confidence = 0` path is
  the whole point of this step.
- **No `if (source == SMS)` outside an adapter package** (CLAUDE.md §0). The
  pipeline is source-agnostic; `pending_transaction.source` is persisted for the
  audit trail, never branched on.
- **Law 3.** `MoneyText` is the only place amounts are parsed. Anything new that
  touches an amount uses `Money`.
- If a schema change is needed, it is **v8** with a `Migration_7_8`, a
  `MigrationV7ToV8Test` and a committed JSON, in the same commit.

---

## 10. Definition of done

`CLAUDE.md` §12 in full, plus specifically:

- [ ] A `PENDING` row for every allowlisted message, matched or not
- [ ] `confidence = 0` and `needs_manual_fill = 1` on the unmatched path, with a
      named test
- [ ] `dedupe_key` stored per §3.1's formula
- [ ] Re-running the worker creates no duplicate pending rows
- [ ] `.\gradlew preMergeCheck` green on **both** flavours
- [ ] Instrumented suites green on the device
- [ ] `SPEC.md` §3.1's running P2 note updated
- [ ] Both guard scripts pass

---

## 11. Environment notes that cost time this session

- `$env:JAVA_HOME = "D:\Software\Android App development\jbr"` — every session.
- `adb`: `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- `bash` is the **WSL** launcher. Call `D:\Software\Git\bin\bash.exe` for the
  guard scripts.
- **Bash heredocs mangle backslash escapes.** This bit repeatedly: `"\n"` in a
  Kotlin string became a literal newline and broke compilation; `\b` in a regex
  vanished; a Python patch script was corrupted the same way. **Write the script
  to the scratchpad with the Write tool and run it**, or build the escape with
  `chr(92) + "n"`. The kickoff has warned about this since S9 and it is still the
  single biggest time sink.
- **XML comments may not contain `--`.** aapt2 rejects the file with a bare
  `ParseError` naming only a column. Cost time twice: the font XMLs and the app
  manifest.
- The Edit tool writes **CRLF**; the repo is LF. Normalise after editing, or use
  a Python script with `newline=""`.
- Running the root `connectedAndroidTest` across all modules flakes on install
  concurrency. Run the seven modules that actually have instrumented tests, with
  `--max-workers=1`.
- **`adb` cannot deliver an SMS.** `am broadcast -a …SMS_RECEIVED` is refused —
  `BROADCAST_SMS` is signature-level. `SmsPduFactory` (androidTestSmsFull) builds
  real PDUs instead; it is self-validating, because the assertions compare what
  Android's parser reads back to what was encoded.

---

## 12. Open items the owner has deferred

Raised, decided against acting on for now. **Do not action without asking.**

1. **`ACCESS_NETWORK_STATE`** arrived with WorkManager, along with `WAKE_LOCK`,
   `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE`. None is `INTERNET`; Law 6
   holds. The *real* gap is that `restrictedPermissionCheck` does not pin the
   full permission set, so the next dependency can add anything unnoticed.
2. **`SENDER_NOT_ALLOWLISTED` retention** — see §8d.
3. **The backup no longer covers every table.** `ExportCoversEveryTableTest`
   counts `List` properties of `BackupPayload` and checks CSV matches; it never
   compares against the *schema*, so v6 and v7 added seven tables and it passed.
   Harmless today. **Stops being harmless the moment P2-4 lands**, because
   `pending_transaction` will hold a user's unreviewed queue and a restore would
   silently drop it. Worth raising again at the start of P2-4.
4. **The branch has never been pushed and there is no PR.**

---

## 13. A process note

Two things this session were built after asking and getting a nod (ADR-0019's
design, the four §16 closures). One was not: **schema v7's
`parser_rule.instrument_hint` was added mid-step without asking**, on the
judgement that shipping a domain field the table could not store was worse. The
owner was told plainly rather than after the fact being discovered. That is the
bar — if a step needs a schema change it did not scope, say so in the same breath
as doing it, or stop and ask.

The corpus is the other lesson. Every synthetic fixture I wrote agreed with the
rules I wrote, and both real messages disagreed with both. **Prefer one real
message to ten invented ones**, and when there are none, say the coverage is
synthetic rather than letting a green run imply otherwise.
