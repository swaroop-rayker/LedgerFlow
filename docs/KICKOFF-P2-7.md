# KICKOFF P2-7 — the pipeline works end to end; what is left is the parts nobody has seen

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the session log for what just shipped, plus the
scope for the next step only.

The owner refers to the P2 steps as **S1…S9**; this file's P2-7 is their "S7".

---

## 1. Session log — twelve commits, and the four bugs real messages found

Branch `s12-testing-matrix-font-cta`, from `bb7e63a`. **Never pushed; no PR.**
66 files, +7586 / −320. **Schema stayed v7 the whole session — no migration.**

| Commit | What |
|---|---|
| `0589b4e` | **P2-4** — `ParseIngestWorker` → `pending_transaction` |
| `2eea934` | **fix** — six ingest tables were missing from every `.lfbk` |
| `6a38491` | **test** — an instrumented assertion that failed its own class |
| `b674483` | **fix** — the sender allowlist matched no real bank SMS |
| `25649ac` | **test** — the payment that exposed it, as a fixture |
| `295f858` | **P2-5** — cross-source dedupe, and §3.1's key rewritten |
| `93861b1` | **docs** — the payment app is silent; notifications are the SMS again |
| `ecaf5f8` | **§16 Q14** — re-triage after an allowlist change, and its trigger |
| `2c2b3db` | **P2-6** — the Inbox, the review screen, Law 1's first real tap |
| `87a4dd6` | **redesign** — review is the entry form now, and never asks the book |
| `7790367` | **test** — the ₹69 payment, and what it proves about "Sent" |
| `d88ca85` | **fix** — capture only worked while the app was open |

### The headline

**The full chain now runs on real hardware.** SMS → capture → parse →
`pending_transaction` → Inbox → Approve → `ledger_entry`, verified on the
owner's SM-S721B with their own bank messages. That had never happened before
this session.

---

## 2. Four bugs, and the one thing they have in common

Every one was found by a real message, and none by a test. **Read this section
before writing any new rule, allowlist entry, or key.**

### 2.1 The sender allowlist matched nothing (`b674483`)

Every v1 pattern was the entity tail — `*-HDFCBK`. SQLite `GLOB` anchors the
whole string, and a real TRAI DLT header is `XX-ENTITY-C` with a trailing route
class: `VM-HDFCBK-T`, `AD-HDFCBK-S`. **No bank SMS on a real Indian device had
ever matched**, through P2-2, P2-3 and P2-4.

Nothing could have caught it: the golden corpus tests the *parser*, and the
parser was never reached, because every fixture supplied a sender the allowlist
had been written against. v2 ships both forms (`*-ENTITY` and `*-ENTITY-[^P]`);
`-P` promotional is excluded on purpose, since §5.1 would turn every bank
marketing SMS into a confidence-0 Inbox row. `*-SBYONO` was missing outright.

**The regression test is instrumented**, because `GLOB` is SQLite's — whether
`[^...]` negation is even supported was verified on the device, not assumed.

### 2.2 §3.1's dedupe key could not match an SMS against a notification (`295f858`)

Measured against the corpus, **both variable components diverge by source**:

| | SMS fixtures | Notification fixtures |
|---|---|---|
| extract `accountLast4` | 14 of 16 | **0 of 5** |
| extract a date | 3 of 16 | **0 of 5** |

`DateText` resolves a bare `On 27/08/26` through `LocalDate.atStartOfDay`, so a
real debit's `occurredAt` is *midnight* while a notification falls back to
capture time. And `accountLast4 ?: merchantNormalized` *guarantees* the two
sources pick different fields.

So each moved to where it works: `dedupe_key` is amount + direction, the ±3
minutes is a range on `created_at`, and the account/merchant/reference
comparison became `DuplicateMatcher` — **contradiction, not agreement**. That is
what `INDEX(dedupe_key, created_at)` was always shaped for.

### 2.3 An allowlist change never re-triaged what it had rejected (`ecaf5f8`)

Fixing 2.1 could not reach anything received before the fix. `retriageRejectedSms`
re-admits rejected SMS to `CAPTURED`, triggered by a **fingerprint of the enabled
patterns** in `app_meta` — one mechanism that covers a shipped seed *and* a P5
Settings edit, and cannot be forgotten at a future call site.

**Building it exposed the gap that mattered**: re-triage runs in the worker, and
the worker was enqueued only by a *capture*. The allowlist changes at launch, so
the fix would have sat unrun until the next SMS. Enqueueing moved behind
`IngestWorkTrigger`, a `:core:domain` port, and `:app` asks after seeding.

### 2.4 Capture only worked while the app was open (`d88ca85`) — the worst one

Everything that opened the vault ran from `AppViewModel`. An SMS arriving with no
Activity alive reached a receiver whose database call failed;
`PersistingIngestEventSink` logged it and **the message was gone** — no raw row,
no retry, nothing to replay. **For the whole of P2, a financial SMS was silently
dropped unless the user happened to have the app open.**

`VaultSession.openForBackgroundWork()` opens the vault if nothing else has. **No
new wrap, no new key material** — it calls the same `openOnLaunch()` the UI does.
A headless unlock is what the hierarchy was designed for: §7 forbids
`setUserAuthenticationRequired(true)` on the DEK-wrapping key *precisely* so the
Keystore unwrap needs no user present.

**Two older tests asserted the bug and had to be rewritten.** Both encoded "a
closed vault drops the write" as correct. A test can defend a defect.

### 2.5 The pattern

Every fixture the ruleset was written against agreed with the ruleset. Every
allowlist entry agreed with the fixtures. **Prefer one real message to ten
invented ones**, and when there are none, say the coverage is synthetic rather
than letting a green run imply otherwise.

---

## 3. Other things that shipped

- **The backup covered 10 of 16 tables** (`2eea934`). `ExportCoversEveryTableTest`
  counted `BackupPayload`'s own fields and never looked at the schema, so it
  stayed green through v6 and v7. It now reads
  `core/database/schemas/{VERSION}.json`, **declared as a task input** — the
  fourth instance of a guard that could not see the thing it guarded.
- **A JUnit trap** (`6a38491`): a test body ending on Truth's `containsExactly`
  returns `Ordered`, so `= runBlocking { … }` infers a non-Unit return type and
  JUnit4 rejects the **whole class** before a single test runs. `preMergeCheck`
  cannot see it; only the device can.
- **The review screen is the entry form** (`87a4dd6`). `LfPickerDialog`,
  `LfChoiceRow` and `LfDetailRow` moved from `:feature:entry` into `:core:ui`,
  and **both screens render the same composables**. The book control is gone —
  "debited" is spend, "credited" is income — except on §5.1's never-drop rows,
  which get a Book row because there is nothing to derive from.
- **Notification ingest captures nothing on the owner's device**, and that is
  correct. Their payment app posts no transaction notifications; what appears is
  the *SMS's own* notification, and no messaging app is on the curated allowlist,
  so §5.2's filter refuses it before any body is read. §3.1's claim that
  notifications capture "strictly more than SMS" is now known to be false for at
  least one real setup (`93861b1`).

---

## 4. Where the plan stands

| # | Step | Status |
|---|---|---|
| S0–S12 | Phase 0 + P1 + `TESTING.md` | ✅ |
| P2-1 … P2-3 | Schema v6/v7, capture, rule engine | ✅ |
| **P2-4** | `ParseIngestWorker` → `pending_transaction` | ✅ |
| **P2-5** | Cross-source dedupe | ✅ |
| **P2-6** | `:feature:inbox` — list, filters, review, approve/discard | ✅ (bulk approve deferred) |
| **P2-7** | Notifications — `inbox_high`, deep link, actions, grouping | ⬅ **next in the plan** |
| P2-8 | Notification-listener permission UX, rebind, health banner | pending |
| P2-9 | Exit criteria — 50+50 corpus, named dedupe test | pending |

### Verified state as of `d88ca85`

- `preMergeCheck` **green on both flavours**; both guard scripts pass.
- **Unit: 450, zero failures.** `:core:domain` 53, `:core:data` 56,
  `:feature:ingest` 41, `:feature:inbox` 15, `:app` 15, others unchanged.
- **Instrumented: 309, zero failures**, swept across all seven modules against
  the final commit — crypto 5, database 73, data 208, designsystem 10, ingest 5,
  onboarding 6, app 2. Counted from the result XMLs rather than the summary
  lines. Baseline at the start of the session was 282.

  The first attempt at this sweep died partway through `:core:data` and failed
  the four modules queued behind it; `adb devices` was empty. **A run of
  consecutive module failures is a disconnected cable until proven otherwise** —
  check before believing it.
- Schema is **v7**. No migration was needed all session.
- Corpus: **20 SMS fixtures (17 matched), 7 notification (5 matched)**. Four SMS
  fixtures are now real messages from the owner's phone; **every notification
  fixture is still synthetic and has never been exercised on a device.**

---

## 5. What already exists for P2-7

| Piece | State |
|---|---|
| `pending_transaction` | Written, read, reviewed, approved. Live on the owner's device. |
| `Destination.InboxReview(pendingId)` | Exists. **The deep link §5.1 wants lands on it** — `ReviewViewModel.PENDING_ID_ARG` is the other half of the contract, guarded by `InboxReviewArgumentTest`. |
| `ObservePendingCountUseCase` | Feeds §9.3's `Inbox (n)` dial. A notification badge would read the same thing. |
| `DiscardPendingUseCase` | Exists — §5.1's `[Discard]` notification action needs no new domain work. |
| `ApprovePendingUseCase` | Exists, idempotent across its two writes. |
| `NotificationIngestService` | Captures, allowlist-gated. **Never granted on the owner's device.** |
| Notification channel `inbox_high` | Does not exist. P2-7 creates it. |
| `POST_NOTIFICATIONS` permission | **Not in the manifest.** Required at API 33+. See §7. |

---

## 6. Scope for P2-7 (SPEC.md §5.1)

```
insert into pending_transaction
  → post Notification (channel "inbox_high", actions: [Review] [Discard])
  → tap → ledgerflow://inbox/{pendingId} → Review screen
  → grouped notification when >3 pending
```

1. Channel `inbox_high`, importance HIGH, **no sound by default** (configurable).
2. Posted when a candidate is created — *not* when one is suppressed. A
   duplicate the user never needs to see must not buzz.
3. Actions `[Review]` and `[Discard]`, both working from the shade.
4. Deep link `ledgerflow://inbox/{pendingId}`.
5. Grouping when more than three are pending.

**Out of scope:** listener permission UX and the health banner (P2-8), the 50+50
corpus (P2-9), bulk approve, and the Settings rule editor.

---

## 7. Decisions to bring to the owner before building

**a. Is P2-7 the right next step at all?** Notifications are the *output* half
here, and they are worth having. But on the owner's device notification *ingest*
captures nothing, all seven notification fixtures are synthetic, and P2-9's exit
criteria need 50 real messages of each. There is a case that **P2-9's corpus
work, or P2-8's permission UX, buys more than a notification the owner may not
want buzzing.** Ask rather than assume the plan order.

**b. `POST_NOTIFICATIONS` is a new runtime permission.** API 33+ requires it and
the app does not declare it. §12.1's deferred item — that
`restrictedPermissionCheck` does not pin the full permission set — becomes live
the moment this is added. Worth pinning the set in the same commit.

**c. What does `[Discard]` from the shade do about undo?** The Inbox has a
snackbar; a notification action has no equivalent surface. Discarding is
reversible (`RestorePendingUseCase`, 30 days) but the user has nowhere to be
told so.

**d. Does approving from a notification exist?** §5.1 lists only `[Review]` and
`[Discard]`. A one-tap approve would be the fastest path for a confident
candidate and is *not* specified — do not add it without asking.

---

## 8. Non-negotiables for this specific work

- **Law 1.** A notification action may discard, and may open the review screen.
  **It may not approve into `ledger_entry` except through
  `ApprovePendingUseCase`**, which is the only path and stays so.
- **Never post content from a non-allowlisted source.** The notification body
  will contain a merchant and an amount that came from a bank message; §5.2's
  privacy rule governs what may be read, and the same care applies to what is
  displayed on a lock screen. Consider what a locked device shows.
- **A suppressed duplicate must not notify** (§3.1). It is retained and visible,
  not announced.
- If a schema change is needed it is **v8** with a `Migration_7_8`, a
  `MigrationV7ToV8Test` and a committed JSON, in the same commit.

---

## 9. Definition of done

`CLAUDE.md` §12 in full, plus:

- [ ] Channel created once, idempotently, and not on every post
- [ ] Deep link opens the right candidate, verified on the device
- [ ] `[Discard]` works from the shade with the app closed — **the capture fix
      means the vault opens itself now, so this is reachable; prove it**
- [ ] No notification for a suppressed candidate
- [ ] `.\gradlew preMergeCheck` green on **both** flavours
- [ ] Instrumented suites green on the device
- [ ] `SPEC.md` §3.1's running P2 note updated
- [ ] Both guard scripts pass

---

## 10. Environment notes that cost time

- `$env:JAVA_HOME = "D:\Software\Android App development\jbr"` — every session.
- `adb`: `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- `bash` is the **WSL** launcher. Call `D:\Software\Git\bin\bash.exe` for the
  guard scripts.
- **Bash heredocs mangle backslash escapes.** Bit again this session: `[^\n]` in
  a Kotlin raw string became a literal newline. **Write the script to the
  scratchpad with the Write tool and run it**, or build the escape with
  `chr(92) + "n"`.
- Run instrumented suites **per module** with `--max-workers=1`. The device
  dropped off USB mid-sweep this session and failed every module queued behind
  it — check `adb devices` before believing a failure.
- The phone locks itself while a long sweep runs, which leaves the app's
  Activity paused behind the lock screen. `uiautomator dump` then returns the
  *lock screen*, not the app, and it looks like the app failed to launch. Check
  `dumpsys window | grep showing=` before concluding anything about the UI, and
  do not try to get past the lock — hand that to the owner.
- **`adb` cannot deliver an SMS.** `BROADCAST_SMS` is signature-level. Only the
  owner can produce a real capture, by making a payment.
- Reading the owner's SMS for diagnosis: `content query --uri content://sms/inbox
  --projection address:date:body --sort 'date DESC'`. `--where` with a bare
  numeric literal is mangled by the shell; filter in the pipeline instead.

---

## 11. Open items the owner has deferred

**Do not action without asking.**

1. **§16 Q16 — a message that arrives when the vault cannot open is still only a
   log line.** The common case is fixed; the residual one (no onboarding, or a
   lost Keystore wrap) needs storage outside the vault and a surface to show it.
   §5.2's Dashboard health banner is the natural home.
2. **`occurred_at` is midnight for SMS-derived entries.** `On 27/08/26` has no
   time, so the ledger row reads `12:00 am`. The date is right; the display is
   odd. Fixing it means deciding what `occurred_at` means when a message gives a
   date but no clock.
3. **The review screen has no BUG6 protection.** The entry form persists to
   `draft_entry` on every keystroke; review holds typing in memory, so a process
   death mid-review loses it. That was the deliberate cost of keeping candidates
   out of the drafts stack — worth revisiting now the form is as long as the
   entry form's.
4. **Bulk approve** (§5.1) — deferred at P2-6. Needs multi-select plus the
   "previously used category" ranking (`EntryCombo` exists).
5. **`verifyRoborazziDebug` does not exist as a Gradle task**, though CLAUDE.md
   §4 lists it and §12 requires "screenshot diffs reviewed". Never wired up.
6. ~~**`restrictedPermissionCheck` does not pin the full permission set**~~
   **CLOSED — P2-7**, in the same commit that added `POST_NOTIFICATIONS`, as §7b
   asked. `EXPECTED_PERMISSIONS` is now an allowlist keyed by manifest path:
   anything declared and not pinned fails, anything pinned and not declared
   fails, and a pin naming a manifest that does not exist fails. The gap it
   closes is the permission nobody thought of — the old task knew three names
   and would have passed a `READ_CONTACTS`, or a `<uses-permission>` merged in
   from a future dependency's manifest, in both flavours silently. Proved by
   breaking it six ways before believing it.
7. **A payee-name-vs-VPA mismatch will not dedupe.** `RAMESH KUMAR` and
   `ramesh@okhdfcbank` normalise differently and read as a contradiction. Errs
   toward showing two rows, which is the safe direction. For P2-9 to revisit
   against a real corpus.
8. **The branch has never been pushed and there is no PR.**

---

## 12. A process note

Four bugs this session, all found by real messages, none by a test — and two of
them had **tests defending the defect**. `aLockedVault_refusesRatherThanThrows`
asserted that a closed vault drops a financial SMS, and it passed for three
steps.

So: when a guard is added or changed, **make it fail on purpose before believing
it**. That was done for the dedupe test, the export coverage test, the re-triage
tests, the book-derivation tests and the capture fix, and it is the only reason
any of them can be trusted. A green test whose red has never been seen is a
claim, not evidence.

The owner's device is the other half. Three of the four bugs were invisible from
the desk — the allowlist because `GLOB` is SQLite's, the JUnit trap because it
happens at class load, and the capture bug because it only appears when nobody
is looking at the app.
