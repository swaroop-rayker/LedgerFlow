# KICKOFF P2-9 — P2 is done bar one real payment

> **Status after the P2-9 session:** the corpus, the dedupe test and Roborazzi
> are all closed. **The single open item is `TESTING.md` F23** — one real UPI
> payment, to prove notification ingest captures anything at all. Read §5 and
> §9 first; the rest is the record of how it got here.

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the session log for what just shipped, plus the
scope for the next step only.

The owner refers to the P2 steps as **S1…S9**; this file's P2-9 is their "S9",
and it is the last one.

---

## 1. Session log — P2-8, four commits and one bug

Branch `s12-testing-matrix-font-cta`, continuing from `a723311`. **Pushed for
the first time this session** — `origin/s12-testing-matrix-font-cta`. Still **no
PR**, and `main` is untouched at `2c61828`, 46 commits behind.

| Commit | What |
|---|---|
| `241e259` | **ADR-0020** — the listener's health, recorded outside the vault |
| `f651682` | **P2-8** — explainer, health banner, Settings row |
| `051bc0b` | **BUG17** — the explainer's heading broke mid-word |
| `6e2a28a` | docs — §5.2 as shipped, BUG17, the P2-8 testing rows |

### The headline

**P2-8 is done, and notification ingest can now capture.** Access is granted on
the owner's device and the listener is bound. What has *still* never happened is
a capture: the Inbox holds nothing from a notification, and all seven
notification fixtures remain synthetic. **The remaining gap is a payment, not a
permission** — which is exactly P2-9's problem.

---

## 2. Two premises from the last kickoff were wrong

Both were stated as fact and both cost time before being checked. The lesson is
the same one §2.5 of the previous kickoff records, moved up a level: **a session
log is not evidence.**

### 2.1 "Notification access is still not granted"

It was granted. `settings get secure enabled_notification_listeners` lists
`com.ledgerflow.debug/…NotificationIngestService`, and the listener has been
binding normally. The whole framing of P2-8 as "the step that unblocks capture"
was therefore half wrong: the grant was never the blocker.

**Check the device, not the log.** One `adb` call would have said so.

### 2.2 "`onListenerDisconnected` → `requestRebind()` is not implemented"

It was implemented, in `88c7223`, and the §5 table said otherwise. What was
genuinely missing was the *persisted timestamp* the banner needs — a much
smaller gap than the table implied.

---

## 3. What P2-8 shipped

**Storage.** ADR-0020: listener health lives **outside the vault**, in a
DataStore file under `filesDir`. The writer is `onListenerConnected`, which the
system calls at boot in a process with no Activity — `CLAUDE.md` §7's exact
trap, and the vault would be unreadable in precisely the states the banner
exists to report. Four keys, guarded by `DatastoreKeySurfaceTest`.

**Whether the listener is bound right now is deliberately not persisted.** A
stored flag would say "connected" forever after the one event worth reporting.
Liveness is answered by the process, duration by the disk.

**The explainer is not an onboarding step**, and §5.2's own sentence says it
should be. §7.4's `completeBackupLocation` is where the vault is *created*, so
the route switches away from onboarding at the instant the last gate step
completes. A declinable step inside a gate designed so nothing can be skipped is
the wrong shape besides. It is the first screen after the vault exists, with two
standing routes back (Home banner, More row) — both required, because an
explainer that exists only during first run cannot be reached by any install
that already completed it.

**The banner needs three states.** "Off" and "has stopped" have different causes
and different fixes; the third is silent and covers the first seconds of every
cold start.

---

## 4. BUG17, and why it is not a BUG9 violation

The explainer's title rendered as "Notificatio" above a lone "n".

**BUG9's countermeasure is what made it fail.** §8 requires control labels to
render `maxLines = 1, softWrap = false`, so "Not now" correctly held its natural
width and refused to shrink. The screen used the shared header —
`Row(LfScreenTitle(weight = 1f), LfButton)` — so the whole cost landed on the
`weight(1f)` column, on a `Text` no countermeasure covered. **A working rule
pushed the failure one element sideways.**

Every other title in the app is one short word, so the pattern had never been
asked to carry a twenty-character one. Fix: the heading gets its own line, which
`CLAUDE.md`'s design brief already prescribed. Cost, measured: header 662px →
776px.

**Do not "fix" this by shortening a title to one line.** Two short words wrap
cleanly at any scale; a single long word ("Notifications") has no break point
and *must* break mid-word. Recorded as countermeasure (c) in §8.

`ExportScreen` and `CategoriesScreen` still use the shared header and were
checked: **both green at font scale 2.0**, and the guards are kept anyway.
Proved worth keeping by changing nothing but `ExportScreen`'s exit label from
"Done" to "Save and close now" — the six-letter word "Export" then broke as
"Expo"/"rt".

---

## 5. Where the plan stands

| # | Step | Status |
|---|---|---|
| S0–S12, P2-1 … P2-7 | | ✅ |
| **P2-8** | Permission UX, rebind, health banner | ✅ **done** — F19 moved to the pre-release matrix (owner's call) |
| **P2-9** | Exit criteria — 50+50 corpus, named dedupe test | ✅ **met**, with one honest gap: no real notification has ever been captured |

**So P2 is complete except for one thing that cannot be engineered.** The corpus
is large enough and marked; the dedupe test has existed since P2-5; the
permission UX, rebind and banner are verified on hardware. What is missing is
evidence: notification ingest has captured **zero** real messages, and one UPI
payment is what changes that. Everything else in P2 has been exercised against
reality at least once.

### Verified state as of `6e2a28a` plus this session's additions

- `preMergeCheck` **green on both flavours**; both guard scripts pass.
- **Unit: 557 (`smsFull`), zero failures.** Was 516.
- **Instrumented: see §9** — the sweep is per module with `--max-workers=1`.
- Schema **v8, unchanged**. P2-8 needed no migration.
- Corpus unchanged: **20 SMS fixtures (4 real), 7 notification (all synthetic,
  still never exercised on hardware).**

---

## 6. Scope for P2-9 (SPEC.md §13, P2 exit criteria)

> 50-SMS + 50-notification golden corpus passing. Dedupe test: same UPI txn via
> both sources → exactly one pending row.

1. Grow the corpus to **50 SMS + 50 notification** fixtures.
2. ~~A named cross-source dedupe test.~~ **ALREADY DONE** — corrected after this
   file was first written. `Dedupe_SameTxnAcrossSources_ProducesOnePending`
   exists in `RawIngestRepositoryInstrumentedTest`, shipped at P2-5, and was
   verified to fail with the mechanism disabled. It asserts one *live* candidate,
   the loser retained and linked by `suppressedById`, and both raw rows' parse
   statuses. Its sibling covers the common ordering, where the richer SMS arrives
   second and the incumbent notification is suppressed in its favour. **Do not
   rebuild it.**

**So the corpus is the whole of what remains**, and it was always the hard half.
§16 Q15 is the precedent, worth re-reading before writing a single fixture:
every SMS fixture supplied a sender the allowlist had been written against, so
the allowlist matched nothing real for three whole steps while every test stayed
green. **Synthetic fixtures test the parser against itself.**

### What the corpus actually holds today

| | Fixtures | Real | Distinct sources | Allowlist admits |
|---|---|---|---|---|
| SMS | 20 | **4** | ~6 bank entities | **26 entities** |
| Notification | 7 | **0** | 4 packages | **20 packages** |

**All four real SMS are HDFC.** So even the real half of the corpus is one bank,
and the notification half has never seen a real message at all. The gap to the
exit criterion is **+30 SMS and +43 notifications** — but the more useful number
is the second column, because that is the one Q15 says decides whether the
corpus proves anything.

---

## 7. Bring to the owner before building

**a. The corpus needs real messages, and only the owner can produce them.**
`adb` cannot deliver an SMS (`BROADCAST_SMS` is signature-level) and cannot post
a notification as another app. 100 fixtures cannot come from one person's
payments in one session. So the question is what "50+50" is allowed to mean:
real messages only (slow, honest, and the only thing that would have caught
Q15), synthetic ones derived from real *shapes*, or a mix with the real ones
marked. **This is the decision that determines whether P2 actually exits.**

**b. F23 is one payment away and has never been done.** Notification access is
granted and the listener is bound, so the very next UPI payment should produce a
`PENDING` row **from the notification**. Nothing in this project has ever
observed that. Ask for one payment before anything else — it is the cheapest
outstanding evidence, and whatever it produces is the first real notification
fixture.

**c. F19 is P2-8's one unclosed row** and needs six hours plus an OEM battery
restriction. Everything around it is covered (see §9). Decide whether it blocks
P2-8 being called done or moves to the pre-release matrix.

**d. Roborazzi still does not exist.** `verifyRoborazziSmsFullDebug` is what
CI's `screenshot` job runs, and `pr-gate` requires it — so **any PR fails today
regardless of the code**. This is now blocking, not merely deferred, because the
branch is pushed and a PR is the next natural step. Wiring it up means recording
and *reviewing* golden screenshots (§12 forbids blind re-recording), which is
its own piece of work.

---

## 8. Non-negotiables for this specific work

- **A real message that fails to parse becomes a permanent fixture** before the
  fix. The corpus only grows (`CLAUDE.md` §11).
- **Fixtures are byte-exact.** `.gitattributes` marks `testdata/**` as `-text`
  for a reason: a CRLF normalisation produces tests that pass on Windows and
  fail on CI, or worse the reverse.
- **The dedupe test is named** and asserts *one* pending row plus one visible
  suppressed row — never a silent drop (§3.1).
- **A payee-name-vs-VPA mismatch will not dedupe** today (§11.6 below). Against
  a real corpus this is the moment to decide whether that stays.

---

## 9. Definition of done

`CLAUDE.md` §12 in full, plus:

- [x] 50 SMS + 50 notification fixtures — **52 and 53**, mixed, every one marked
- [x] ~~The named cross-source dedupe test~~ — already existed, see §6
- [ ] **At least one real notification captured end to end on the device** —
      the one item still open, and the only one no amount of work here can
      close. `TESTING.md` F23; the owner deferred the payment to later.
- [x] `SPEC.md` §13's P2 exit criteria marked met, with the composition stated

### Decided at P2-9, so nobody relitigates them

| Question | Answer |
|---|---|
| What may "50 + 50" mean? | **Mixed, with the real ones marked** and a floor that only goes up |
| Does F19 block P2-8? | **No** — moved to the pre-release matrix, beside BUG2 |
| Roborazzi? | **Wired properly.** Eight goldens, reviewed; `preMergeCheck` runs CI's exact task |
| F23, the real payment? | **Deferred by the owner to later.** Still the one open item |

### What P2-8 already closed, so P2-9 does not re-litigate it

| Claim | Covered by |
|---|---|
| The > 6 h rule | `ListenerHealthEvaluationTest`, at the millisecond boundary |
| The DEAD banner's wording | `DashboardBannerContentTest`, on device |
| The grant confirmed on resume | `TESTING.md` F18, both directions, on device |
| `requestRebind()` fires | F20 — 5 ms recovery on a real Samsung ROM |
| `requestRebind()` against a revoked grant | F25 — fails quietly, no retry storm |
| The privacy rule is verbatim | `PrivacyRuleIsVerbatimTest`, reads `SPEC.md` |
| A heading never breaks mid-word | BUG17's three suites |

**Only F19 is genuinely open**, and only its six hours.

---

## 10. Environment notes that cost time

- `$env:JAVA_HOME = "D:\Software\Android App development\jbr"` — every session.
- `adb`: `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- `bash` is the **WSL** launcher. Call `D:\Software\Git\bin\bash.exe` for the
  guard scripts.
- **`--tests` does not work on `connectedAndroidTest`.** Use
  `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. This cost a
  round-trip.
- **`composeRule.setContent` may be called once per test.** A loop over states
  throws on the second pass — one test per state, or a mutable state holder.
- Run instrumented suites **per module** with `--max-workers=1`, and check
  `adb devices` before believing a run of failures.
- **`uiautomator dump` + coordinate taps**: guard every tap on a *fresh* dump.
  A guard that refuses when the expected marker is absent, or when the text
  matches more than one node, is worth the fifteen lines — it caught a wrong
  screen this session instead of tapping blind.
- The device runs **font scale 1.15 with density override 480**, which is why a
  layout can be fine in a preview and broken in the owner's hand.
- PowerShell: a function's `Write-Output` is captured by `$x = f`. Use
  `Write-Host` for progress lines.
- **Robolectric's own downloader does not use Gradle's network config.** On this
  box TLS is intercepted (Norton) and `~/.gradle/gradle.properties` points Gradle
  at a custom truststore; the forked test JVM does not inherit it, so Robolectric
  fails with `SunCertPathBuilderException` while Gradle resolves from the same
  host fine. `:core:designsystem`'s build now forwards those two system
  properties into the fork. If a new module gains Robolectric, it needs the same
  four lines.
- **`git grep` for CRLF checks the working tree, not the index.** CI checks out
  fresh, so what matters is the index — `git show :<path> | grep $'
$'`. The
  working tree here is full of CRLF and the index is clean, so the obvious check
  reports a repository-wide failure that does not exist. Cost a false alarm.
- **`preMergeCheck` now takes ~16 minutes**, up from ~2, because Robolectric
  renders eight screenshots twice over. That is the price of the §12 gate.

---

## 11. Open items the owner has deferred

**Do not action without asking.**

1. **§16 Q16** — a message arriving when the vault cannot open is still a log
   line. ADR-0020 built the shelf it needs; the counter itself is unbuilt.
2. **`occurred_at` still *stores* midnight.** Only display and sort are blended.
3. **The two full lists remain** — Inbox and the Ledger's Unsaved section.
4. **Bulk approve** (§5.1) — deferred at P2-6.
5. ~~**Roborazzi does not exist.**~~ **CLOSED at P2-9.** `verifyRoborazziSmsFullDebug`
   exists, eight goldens are recorded and reviewed, and `preMergeCheck` runs the
   same task CI's `screenshot` job does. Three things cost time and are worth
   knowing: Roborazzi **1.32 does not work on AGP 9** (it wants the removed
   `TestedExtension`; 1.73.0 does), Robolectric **cannot emulate SDK 36 on a
   Java 17 toolchain**, and it downloads its platform jar with its *own* HTTP
   client — which fails on this box's intercepted TLS while Gradle resolves from
   the same host fine. The build now forwards the daemon's truststore system
   properties into the forked test JVM, which is a no-op on a normal runner.
6. **A payee-name-vs-VPA mismatch will not dedupe.** For P2-9, against real data.
7. **No PR, and `main` is 46 commits behind.** The branch is pushed.
8. **Four `lf-test-bug6-*.db` files** accumulate in `databases/` on the device
   from instrumented runs. Harmless, never cleaned up.

---

## 12. A process note

P2-8's bugs were not found by reading code. BUG17 came from the owner looking at
a screen. The two wrong premises in §2 came from trusting a written log instead
of asking the device a question that took one `adb` call.

Every guard added this session was **made to fail on purpose**, and twice the
failure taught something the passing version had not: the datastore round-trip
test was rewritten after realising a "fresh instance" read proves nothing
(`preferencesDataStore` is a `Context` delegate — both instances share one
object and read from memory), and the Export title guard only earned its keep
once a one-word button change was shown to break a six-letter title.

**When a guard passes on the first try, ask what it would take to make it
fail.** If the answer is not obvious, it is probably not asserting what its name
claims.
