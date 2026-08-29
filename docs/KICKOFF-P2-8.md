# KICKOFF P2-8 — the output half shipped; the input half is still ungranted

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the session log for what just shipped, plus the
scope for the next step only.

The owner refers to the P2 steps as **S1…S9**; this file's P2-8 is their "S8".

---

## 1. Session log — fifteen commits, and four bugs

Branch `s12-testing-matrix-font-cta`, continuing from `2b1933b`. **Never pushed;
no PR.** 67 files, +8396 / −220, 29 of them new.

**Schema moved: v7 → v8.** One column, `pending_transaction.review_draft_json`,
with `Migration_7_8`, `MigrationV7ToV8Test` and a committed `8.json`. It ran
against the owner's real vault on device and their data came through.

| Commit | What |
|---|---|
| `acc5f85` | **BUG13** — a shade action on a closed vault said nothing |
| `88c7223` | **P2-7** — the notification, and the one row it must not announce |
| `198bb4b` | deep link, `POST_NOTIFICATIONS`, and a guard that pins the whole permission set |
| `d03f562` | docs — §5.1 gains `[Approve]` |
| `c06eae4` | docs — `TESTING.md` rows F8–F16, G5–G6 |
| `349b42c` | **fix** — the group summary raced the notification it was counting |
| `0a426fb` | **BUG14**, schema v8 — a half-reviewed candidate survives a back press |
| `85be8d7` | **CHANGE#1** — erase discarded / failed / suppressed candidates for good |
| `0108476` | **CHANGE#2** — a "To review" band, and the spec decision it reverses |
| `960b5ee` | **CHANGE#1'** — one Unsaved section; date and time on every unreviewed row |
| `8dfcf23` | **fix** — the Unsaved sort disagreed with the times it was showing |
| `6911ce5` | **fix** — TalkBack said "Draft" twice on a draft row |
| `8718878` | **declutter** — offer the filters that hold something; say when a discard is final |
| `5e33bc9` | **BUG15** — a correction reached the review screen and nowhere else |
| `2aeb128` | **BUG16** — reopening an edited candidate wiped its own draft |

### The headline

**P2-7 is done and verified on hardware.** Channel `inbox_high` is live on the
device with the right importance and no sound; the deep link opens the right
candidate from a *cold start*; `[Approve]` `[Review]` `[Discard]` are wired, with
`[Approve]` an owner-approved amendment to §5.1.

**What has still never happened is a notification arriving from a real
payment**, because that needs one to land while the app is closed. `TESTING.md`
F8–F11 remain unrun. See §7.

---

## 2. Four bugs, and what they have in common

Every one was found by running the thing, not by reading it. **Three of the four
had a passing test over them at the time.**

### 2.1 BUG13 — a shade action on a closed vault said nothing (`acc5f85`)

`d88ca85` gave `DefaultRawIngestRepository` a background unlock. **`DefaultPendingRepository`
— which every notification action goes through — was never touched.** Its
one-shots called `requireDatabase()` inside `runCatching { }.getOrDefault(false)`,
so with the app closed a `[Discard]` returned a clean `false` and the row stayed
`PENDING`. §2.4's silent drop, arriving through the Inbox instead of capture.

**`findApprovedEntryId` was the dangerous one.** It is the idempotency guard
across an approval's two writes, and its swallowed null is indistinguishable
from "no entry yet" — so fixing `find` alone would have turned an action that
did nothing into one that writes a **second `ledger_entry` for one payment**.
All five doors moved together.

### 2.2 The group summary raced the notification it counted (`349b42c`)

`notify()` is a **oneway** binder call; `activeNotifications` is a synchronous
read of the same service. `updateGroupSummary()` asked immediately after
posting, so the notification it was called about was routinely not visible yet
and the count came back one short. It showed up as **four tests failing and five
passing on the same code path** — the signature of a race. No unit test can see
it; off-device there is no `NotificationManagerService` to race.

### 2.3 BUG15 — a correction reached the review screen and nowhere else (`5e33bc9`)

Reported as "the autosave seems to be not working". It *was* working. The Inbox
and Ledger rows rendered `extracted` — the parser's values — so an edited
candidate looked untouched everywhere except inside the review screen.

The cause was a scoping mistake made when v8 landed: the payload was written as
`:feature:inbox`'s own, borrowing §6.1.2's split for `draft_entry.payload_json`.
**That split holds for a draft and not for a candidate**, because a candidate is
a row other surfaces list — and the very next commit put candidates on the
Ledger. The argument was already false when it was written down.

### 2.4 BUG16 — reopening an edited candidate wiped its own draft (`2aeb128`)

Reported as "after pressing back for the **2nd** time it resets again", which is
exactly the sequence that finds it. Reopening set the baseline to the state
*including* the saved edits, so the first debounce tick read "nothing has
changed since I opened" as "the user undid everything" and **cleared the row**.
The screen still showed the typing from memory, so the first reopen looked
right and only the second was blank.

### 2.5 The pattern

**A test can be green because it is asking the wrong question.** Four instances
this session:

- `containsNoneIn(suppressedRawIds)` compared raw ids against candidate ids —
  two key spaces that cannot overlap, so it passed whatever the code did.
- A test compared `decode(x)` to `decode(x)`. That assertion could never fail.
- Every BUG14 test asserted the **in-memory state** after reopening — precisely
  the half that stayed correct while the row on disk was cleared.
- The Unsaved sort and its display were each right about the value they were
  handed, and only the two together were wrong (`8dfcf23`).

Sabotage caught all four. **Make the guard fail on purpose, and check *which*
tests fail** — the set that goes red is the assertion, not the count.

---

## 3. Other things that shipped

- **`restrictedPermissionCheck` pins the whole permission set** (`198bb4b`),
  closing the old §11.6. It knew three names; `EXPECTED_PERMISSIONS` is now an
  allowlist keyed by manifest path, so a `READ_CONTACTS` or a transitive
  `<uses-permission>` fails. Test manifests are walked rather than exempted.
  **Proved by breaking it six ways.**
- **`TaxonomySingleWriterTest` caught a commit.** Its regex matches any
  `.purge(`, so `PendingRepository.purge` made `InboxUseCases.kt` an offender.
  Widening the permitted set would have *also* permitted a taxonomy purge from
  that file, so the operation was renamed to `erase` — the same move this
  codebase already made for `hardDelete`, now recorded in that test's KDoc.
- **`occurred_at` is midnight for every SMS-derived row.** All twenty fixtures,
  including the four real ones, state a date and no clock. `OccurredAt.effective`
  in `:core:common` blends the message's *day* with the capture *time*;
  **display only — the stored column is unchanged.** Both the formatter and the
  Unsaved sort use it, which is what `8dfcf23` fixed.
- **The Inbox chip row is adaptive.** `FAILED` is empty by construction and
  `Suppressed` usually is, so chips are drawn by **count** — never by a rule, so
  `FAILED` returns by itself the day something writes one.
- **A draft's discard now says it cannot be undone.** It is a hard
  `draftEntryDao().delete` and does **not** reach the bin; a candidate's discard,
  one row away in the same section, is restorable for 30 days.

---

## 4. Where the plan stands

| # | Step | Status |
|---|---|---|
| S0–S12, P2-1 … P2-6 | | ✅ |
| **P2-7** | Notifications — `inbox_high`, deep link, actions, grouping | ✅ **verified on device** |
| **P2-8** | Notification-listener permission UX, rebind, health banner | ⬅ **next in the plan** |
| P2-9 | Exit criteria — 50+50 corpus, named dedupe test | pending |

### Verified state as of `2aeb128`

- `preMergeCheck` **green on both flavours**; both guard scripts pass.
- **Unit: 516 (`smsFull`), zero failures.** Was 450 at session start.
- **Instrumented: 346, zero failures**, all seven modules, device present at
  every checkpoint. Was 309.
- Schema **v8**. Migrated the owner's real vault on device; Income entries
  survived, no Recovery screen, no LedgerFlow StrictMode violations.
- Corpus unchanged: **20 SMS fixtures (4 real), 7 notification (all synthetic,
  never exercised on hardware).**

---

## 5. What already exists for P2-8

| Piece | State |
|---|---|
| `NotificationIngestService` | Declared, binds, allowlist-gated. **Access never granted on the owner's device.** |
| `GetIngestSourceStatusUseCase` | Reads every source through `IngestSourceStatus` — `UNSUPPORTED_IN_BUILD` / `UNAVAILABLE_ON_DEVICE` / `PERMISSION_REQUIRED` / `READY`. **This is what a banner or a Settings row reads.** |
| `NotificationAdapter.status()` | Already answers `PERMISSION_REQUIRED` via `NotificationManagerCompat.getEnabledListenerPackages()`. |
| `POST_NOTIFICATIONS` | Declared, pinned, and requested once at `AppRoute.Ready`. **That request is the minimum, not the UX** — P2-8 should replace it. |
| Dashboard | Still `DashboardScreen()` with no ViewModel — a placeholder. §5.2's health banner has no host yet. |
| `onListenerDisconnected` / `requestRebind()` | **Not implemented.** |

---

## 6. Scope for P2-8 (SPEC.md §5.2)

> **Permission UX:** `NotificationListenerService` cannot be granted in-app.
> Onboarding deep-links to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` with
> an explainer screen, then polls `NotificationManagerCompat.getEnabledListenerPackages()`
> on resume to confirm. Service disconnection (OEM battery killers are aggressive
> here) is detected via `onListenerDisconnected()` → `requestRebind()`, plus a
> Dashboard health banner if the service has been dead > 6 h.

1. An explainer screen stating §5.2's privacy rule **verbatim**, deep-linking to
   the system settings page.
2. Confirm the grant on resume by polling.
3. `onListenerDisconnected()` → `requestRebind()`.
4. A Dashboard health banner when the listener has been dead > 6 h.
5. Replace the bare `POST_NOTIFICATIONS` request with real UX.

**Out of scope:** the 50+50 corpus (P2-9), bulk approve, the Settings rule
editor.

---

## 7. Bring to the owner before building

**a. Is P2-8 the right next step?** It is the step that would make notification
ingest capture anything at all on their device, so it unblocks P2-9's
notification half — but the owner has now twice chosen differently from the plan
order. Ask.

**b. `TESTING.md` F8–F11 have never been run**, and they need a payment landing
while the app is **closed**. The owner made payments *during* this session (a
new candidate appeared at 6:37 pm) — **nobody checked whether a notification
fired.** That is the cheapest outstanding evidence in the project: ask what they
saw. If nothing appeared, that is a P2-7 defect and it comes before P2-8.

**c. Does the health banner need somewhere to live?** The Dashboard is a
placeholder with no ViewModel. A banner is its first real content, which is
either a small scope creep or the natural moment to give that screen a state.

**d. §16 Q16 is still open and P2-8 is its natural home.** A message arriving
when the vault cannot open is still only a log line. It needs storage *outside*
the vault (a DataStore counter) and a surface — and §5.2's health banner is that
surface. Doing them together is cheaper than doing them apart. **Do not action
without asking.**

---

## 8. Non-negotiables for this specific work

- **The allowlist filter runs before any notification body is read.** §5.2's
  privacy rule is a stated guarantee, and the explainer screen quotes it — so
  the screen and the code have to agree.
- **Never grant, or appear to grant, notification access in-app.** It is a
  Settings-only grant; the app deep-links and then *confirms*.
- **The picker shows labels and package names only** (D-10). It cannot show
  content: for a package not on the list, nothing was read.
- **A dead listener must not look like an empty Inbox.** That is the whole
  reason the banner exists.
- If a schema change is needed it is **v9** with a `Migration_8_9`, a
  `MigrationV8ToV9Test` and a committed JSON, in the same commit.

---

## 9. Definition of done

`CLAUDE.md` §12 in full, plus:

- [ ] The explainer states §5.2's rule verbatim and reaches the system page
- [ ] The grant is confirmed on resume, on the device
- [ ] `requestRebind()` fires on disconnection — **force-stop the listener and
      prove it**, since OEM battery-killers are the reason it exists
- [ ] The banner appears after > 6 h dead and disappears on reconnect
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
- **Bash heredocs mangle backslash escapes.** Write the script to the scratchpad
  and run it.
- Run instrumented suites **per module** with `--max-workers=1`. **Check
  `adb devices` before believing a run of failures** — the cable dropped twice
  this session, once mid-sweep.
- **`adb` cannot deliver an SMS.** `BROADCAST_SMS` is signature-level. Only the
  owner can produce a real capture, by making a payment.
- **`uiautomator dump` + coordinate taps are how the UI was verified**, and they
  are sharp. Two rules learned the hard way this session: **guard every tap on a
  fresh dump** (a tap computed from a stale one can hit `Approve`), and **verify
  which screen you are on before asserting text is absent** — `text="Review"`
  matches the Review *button* on an Inbox row as well as the review screen.
  Use `text="Paid with"` for the review screen and `text="Pending` for the Inbox.
- PowerShell: a function's `Write-Output` is captured by `$x = f`. Use
  `Write-Host` for progress lines.

---

## 11. Open items the owner has deferred

**Do not action without asking.**

1. **§16 Q16 — a message arriving when the vault cannot open is still a log
   line.** See §7d; P2-8 is its natural home.
2. **`occurred_at` still *stores* midnight.** Only the display and the sort are
   blended (`OccurredAt.effective`). Changing what the column means is a
   decision about ledger data and was deliberately not taken.
3. **The two full lists remain.** The Inbox screen and the Ledger's Unsaved
   section both list candidates. The owner scoped short of collapsing them; the
   remaining move is to make the Ledger section a capped glance that opens the
   Inbox, plus a §5.1/§5.4 amendment.
4. **Bulk approve** (§5.1) — deferred at P2-6. Multi-select now exists for
   erasing, so the machinery is half-built.
5. **`verifyRoborazziDebug` does not exist**, though `CLAUDE.md` §4 lists it and
   §12 requires "screenshot diffs reviewed". Never wired up.
6. **A payee-name-vs-VPA mismatch will not dedupe.** For P2-9 against a real
   corpus.
7. **The branch has never been pushed and there is no PR.** Fifteen commits now.
8. **Two candidates vanished during this session** — not pending, discarded,
   failed, suppressed or approved. Nothing reached the ledger. Removing one
   entirely takes discard-then-erase behind a Warning dialog, so it was most
   likely the owner testing; a sloppy coordinate tap from an earlier script
   cannot be fully ruled out. Worth confirming they were not expected.

---

## 12. A process note

Every bug this session was found by *running* the thing — on the device, or by
sabotaging a guard and watching which tests went red. **Three of the four had a
green test sitting over them.**

The sharpest lesson is §2.5: a test can be green because it asks the wrong
question. The BUG14 suite asserted the in-memory state after reopening and was
completely blind to the row being cleared on disk; it took the owner's precise
report — "the **2nd** time" — to locate it.

So: when a guard is added, **make it fail on purpose and read the list of what
failed.** If the set that goes red is not the set you expected, the test is
measuring something else.
