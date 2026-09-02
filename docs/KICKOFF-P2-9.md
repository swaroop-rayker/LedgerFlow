# KICKOFF P2-9 — the input half is open; the corpus is what closes P2

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
| **P2-8** | Permission UX, rebind, health banner | ✅ **verified on device**, one row outstanding (F19) |
| **P2-9** | Exit criteria — 50+50 corpus, named dedupe test | ⬅ **next, and the last P2 step** |

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
2. A named cross-source dedupe test proving one payment through both sources
   produces exactly one pending row.

**The hard part is not the test, it is the corpus.** There are 7 notification
fixtures and all of them are synthetic — written against a parser that had never
seen a real notification. §16 Q15 is the precedent and it is worth re-reading
before writing a single fixture: every SMS fixture supplied a sender the
allowlist had been written against, so the allowlist matched nothing real for
three whole steps while every test stayed green. **Synthetic fixtures test the
parser against itself.**

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

- [ ] 50 SMS + 50 notification fixtures, whatever §7a settles them to be
- [ ] The named cross-source dedupe test, green
- [ ] At least one **real** notification captured end to end on the device
- [ ] `SPEC.md` §13's P2 exit criteria marked met, or amended with reasons

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

---

## 11. Open items the owner has deferred

**Do not action without asking.**

1. **§16 Q16** — a message arriving when the vault cannot open is still a log
   line. ADR-0020 built the shelf it needs; the counter itself is unbuilt.
2. **`occurred_at` still *stores* midnight.** Only display and sort are blended.
3. **The two full lists remain** — Inbox and the Ledger's Unsaved section.
4. **Bulk approve** (§5.1) — deferred at P2-6.
5. **Roborazzi does not exist** and is now blocking a PR. See §7d.
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
