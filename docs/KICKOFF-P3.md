# KICKOFF P3 — Analytics, and the first schema change since ingest

**Read `CLAUDE.md` in full before your first edit.** `SPEC.md` is what to build;
`CLAUDE.md` is how. This file is the session log for what just shipped, plus the
scope for the next phase only.

P2 is the owner's "S1…S9" and it is finished. P3 has no S-numbering yet.

---

## 1. Session log — P2-8 and P2-9

Branch `s12-testing-matrix-font-cta`, **pushed**, at `726c65c`. Still **no PR**,
and `main` is untouched at `2c61828`, 50 commits behind.

| Commit | What |
|---|---|
| `241e259` | **ADR-0020** — the listener's health, recorded outside the vault |
| `f651682` | **P2-8** — explainer, health banner, Settings row |
| `051bc0b` | **BUG17** — the explainer's heading broke mid-word |
| `6e2a28a` | docs — §5.2 as shipped, the P2-8 testing rows |
| `f685510` | tests — what the banner says; the header shape on two more screens |
| `913211c` | docs — P2-8 closed out |
| `eb8b917` | **P2-9** — the 50+50 corpus, marked for what it actually is |
| `2c8cdef` | **Roborazzi** — the screenshot gate CI has always required |
| `726c65c` | docs — P2 met |

### Verified state

- `preMergeCheck` **green on both flavours**; both guard scripts pass.
- **Unit: 557+ (`smsFull`)**, zero failures. **Instrumented: 375** across eleven
  modules, zero failures, device present at all 22 checkpoints.
- Schema **v8**, unchanged since P2-7.
- Corpus: **52 SMS + 53 notifications**, every fixture provenance-marked.
- Screenshot gate: **8 goldens**, reviewed, in `:core:designsystem`.

### The one thing P2 did not close

**No real notification has ever been captured.** Access is granted and the
listener is bound; the pipeline has simply never seen a real message.
`TESTING.md` **F23** is one UPI payment. `CorpusProvenanceTest`'s
`MINIMUM_REAL_NOTIFICATIONS` is **0**, honestly, and F23 is what raises it.

**Do this first if the owner is willing.** It is minutes of work, it is the
cheapest outstanding evidence in the project, and P3 does not depend on it.

---

## 2. What P3 is

`SPEC.md` §13: *rollup table + worker, all chart views, filters, period
comparison, budgets + alerts.* Exit criterion: **5Y query < 300 ms.**

The substance is §5.6 (analytics), §5.7 (budgets), §6.1 (`daily_rollup`), §11
(performance budget).

### This is the first schema change since P2-7

**`daily_rollup` and `budget` do not exist.** Schema v8 has sixteen tables and
neither is among them, so P3 opens with **v9**: a `Migration_8_9`, a
`MigrationV8ToV9Test`, and a committed `9.json`, **in the same commit**
(`CLAUDE.md` §6, §7).

`CLAUDE.md` §7's migration rules are not optional and the failure mode is not a
red test — it is an unreadable vault on the owner's next launch:

- `CREATE new / INSERT SELECT / DROP old / RENAME`, never `ALTER` chains.
- `PRAGMA foreign_key_check` after; a violation aborts and rolls back.
- `PreMigrationGuard` writes and verifies a snapshot first (ADR-0019 — a **file
  copy**, not a `.lfbk`; do not "fix" that back).
- The owner's device holds **real data**. It has migrated v7→v8 successfully
  once; that is the bar.

`daily_rollup` is derived data, so v9 is unusually forgiving — a wrong rollup can
be rebuilt from `ledger_entry`. **`budget` is not derived**: it is user intent
and nothing else in the app can reconstruct it.

---

## 3. Two ADRs are open and both block implementation

`CLAUDE.md` §6 step 2: if the change alters architecture or a dependency, the
ADR comes **first**.

### ADR-0005 — charting library

`CLAUDE.md` §9 bans MPAndroidChart and any View-based chart: **Compose-native
only**. §11 requires charts to get **pre-binned** data, never more points than
horizontal pixels. §10 says propose a dependency, do not adopt one — and note
that a chart library is a *release* dependency, unlike Roborazzi which was
test-only. Law 6 (no `INTERNET`) and §11's APK budget both bear on it.

Drawing the four chart types by hand on Canvas is a real option and should be
argued, not dismissed: donut, stacked bar, horizontal bar and a calendar heatmap
are not a large surface, and they would carry no dependency at all.

### ADR-0006 — rollup strategy

Incremental triggers vs worker-driven rebuild. §5.6 says *"rebuilt incrementally
by `RollupWorker` on every ledger write and reconciled nightly"*, which is
already a hybrid — the ADR is about where the line sits and what reconciliation
actually does when it disagrees with the incremental state.

**Law 1 bears on this.** Only `ApproveTransactionUseCase` may insert into
`ledger_entry`, and approval is already one transaction that updates rollups
(`CLAUDE.md` §5, Room section). Whatever this ADR decides must keep that true —
and `LedgerSingleWriterTest` guards all four doors into that table, so a fifth
writer arriving through the rollup path needs the same guard on the day it
appears.

---

## 4. The open question P3 inherits, and must answer

**§5.6's `txn_count` is ambiguous and `SPEC.md` says so.** ADR-0018 feeds
rollups at **line grain**: a ₹1,000 bill split ₹600/₹400 contributes to two
`daily_rollup` rows. One entry contributing to three category rows is **one**
transaction, not three, and the rule was deliberately left undecided because it
concerned a table that did not exist. It exists now, so P3 decides it.

The same grain question decides budgets: §5.6 is explicit that budgets read line
grain, *"or a ₹400 kettle inside a grocery bill lands in the grocery budget"*.

---

## 5. Bring to the owner before building

**a. ADR-0005 and ADR-0006** — see §3. Both are the owner's call, both block
code, and one of them adds a shipped dependency.

**b. `txn_count`'s meaning** — see §4. It is a one-sentence decision with a
schema consequence, so it wants deciding before `9.json` is committed rather
than after.

**c. F23, the real payment** — see §1. Not a P3 dependency; just the cheapest
outstanding thing in the project.

**d. §16 Q17's two truncation defects.** Both truncate real payees. Fixing them
means editing the **shipped ruleset**, which is a behaviour change for every
install and needs a ruleset version decision plus a re-triage pass (Q14's
mechanism already exists). Not P3 work, but P3 is a natural moment to schedule
it, and the fixtures that pin the current behaviour are already committed.

**e. Still no PR, and `main` is 50 commits behind.** Roborazzi's absence was the
blocker and is now closed — `verifyRoborazziSmsFullDebug` exists and
`preMergeCheck` runs it. So a PR would now run CI for the first time ever, on
Linux, across eight jobs. `instrumented` runs an emulator matrix at **API 26 and
36**, and this codebase has only ever been tested on one physical device at 36.
API 26 is a genuinely different Keystore implementation, and that job is the one
`CLAUDE.md` §11 forbids making advisory. Expect it to find things.

---

## 6. Non-negotiables for this specific work

- **Law 2.** Every rollup query filters on `ledger`. `daily_rollup`'s primary key
  leads with `(local_date, ledger, …)` for exactly that reason. No chart, no
  total, no budget may combine DEBIT and CREDIT.
- **Law 3.** `sum_minor` is `Long`. σ/μ ratios in recurring detection and chart
  *coordinates* are legitimately real-valued; money is not.
- **Analytics reads `daily_rollup`, never `ledger_entry`** (`CLAUDE.md` §8).
  Drill-downs read base tables via Paging 3.
- **Charts get pre-binned data** — never more points than horizontal pixels.
- **No destructive migrations.** See §2.
- `:core:domain` takes **`paging-common` and nothing else** from AndroidX
  (ADR-0014). A second AndroidX coordinate reopens that ADR rather than widening
  the rule — and a charting library will be tempting to put there. It belongs in
  `:feature:*`.

---

## 7. Definition of done

`CLAUDE.md` §12 in full, plus:

- [ ] ADR-0005 and ADR-0006 written and accepted **before** the code
- [ ] Schema v9: `Migration_8_9` + `MigrationV8ToV9Test` + committed `9.json`,
      one commit, and the owner's real vault migrated on device
- [ ] `txn_count`'s meaning decided and recorded in `SPEC.md` §5.6
- [ ] 5Y query **< 300 ms**, measured on the device, not estimated
- [ ] Rollups reconcile: a deliberately corrupted rollup is repaired by the
      nightly pass, with a named test
- [ ] Budgets survive backup → restore round-trip (they are **not** derived)
- [ ] Screenshot goldens for the new chart views, **reviewed** (§12)

---

## 8. Environment notes that cost time

- `$env:JAVA_HOME = "D:\Software\Android App development\jbr"` — every session.
- `adb`: `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- `bash` is the **WSL** launcher. Call `D:\Software\Git\bin\bash.exe` for the
  guard scripts.
- **`--tests` does not work on `connectedAndroidTest`.** Use
  `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`.
- **`composeRule.setContent` may be called once per test.** A loop over states
  throws on the second pass.
- **`preMergeCheck` now takes ~16 minutes**, up from ~2 — Robolectric renders
  eight goldens twice over. That is what the §12 gate costs.
- **Robolectric's downloader does not use Gradle's network config.** This box's
  TLS is intercepted (Norton); `:core:designsystem`'s build forwards the
  daemon's truststore into the forked test JVM. A new Robolectric module needs
  the same four lines.
- **`git grep` for CRLF reads the working tree, not the index.** CI checks out
  fresh, so the index is what matters: `git show :<path>`. Checking the wrong one
  reports a repository-wide failure that does not exist.
- Run instrumented suites **per module** with `--max-workers=1`, and check
  `adb devices` before believing a run of failures.
- The device runs **font scale 1.15, density override 480**, which is why a
  layout can be fine in a preview and broken in the owner's hand.

---

## 9. A process note

P2's last two sessions found bugs in three ways, and none of them was reading
code: the owner looked at a screen (BUG17), a guard was sabotaged and the list of
what went red was checked (six of eight goldens, five of nine banner
assertions), and **a corpus was expanded past what its authors had imagined**
(§16 Q17's two truncation defects, neither reachable by the 27 fixtures that
existed).

Two premises from a previous kickoff were also simply wrong — notification
access was recorded as never granted when it was granted, and `requestRebind()`
as unimplemented when it had shipped. Both cost time. **Check the device, not
the log**, and when a guard passes on the first try, ask what it would take to
make it fail.
