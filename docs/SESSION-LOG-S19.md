# Session log — S19 (2026-10-01 → 10-02): P5 steps 4–5 — every screen has a golden, CI tells the truth, target 37

Starts where S18's log ends (step 3, `021272b`/`736e55c`). Schema **v11,
unchanged** throughout. Step 4 (accessibility pass and screen goldens) and the
V2 merchant-category decision were committed in S18's session but logged only
in their commit messages; they are recorded here. Step 5 (`targetSdk` 37) is
done on Android 16; its Android 17 pass waits for the phone's update.

## 1. Step 4 — a golden for every screen (`33af7a0`, `537fb17`)

- **One harness.** `captureScreenGolden` in `:core:testing` is the one home of
  `LfScreenshotOptions` (three copies deleted). It pins the time zone to UTC
  for the capture and runs three checks before comparing: no two tap targets
  share a touch area, every tappable node is labelled, and no unwrappable text
  is clipped. `AccessibilityChecksTest` proves each can fail.
- **72 new goldens**, every screen without one, at font scale 1.0 and 2.0.
  What they found (SPEC §8): **BUG40** "1 recurring charges"; **BUG41** axis
  dates drawn over each other at 2.0; **BUG42** every third phrase word laid out
  0 dp wide at 2.0, on the three screens where a person types the 24 words;
  **BUG43** base-currency and backup rows too small to choose from safely;
  **BUG44** five clipped control labels, shortened.
- **`LfAdaptiveRow`** (owner-delegated): name-and-amount rows drop the amount
  below the name only when both will not fit, everywhere.
- **`moduleDependencyRuleCheck`**: CLAUDE.md §3 had claimed a Gradle check for
  the module rule that did not exist. It does now, in `preMergeCheck`.
- **Compose's accessibility checker** reported nothing off-device, so it was
  removed. Contrast stays on `TESTING.md` H4 (SPEC §16).
- Harness lessons, each a test that passed on broken code first: Robolectric
  needs `GraphicsMode.NATIVE` or text measures zero wide; render at the width
  the host really gives; static `PagingData` needs finished load states.

## 2. V2 merchant categories, decided (`f871464`, docs only)

Owner, 2026-10-01 (V2-PLAN §5): a flat merchant taxonomy, separate from
spending categories, assigned by hand in Merchants only. Capture, review and
entry never categorise. Analytics planned as DATAVIZ A11, grouped through
`merchant.merchant_category_id` at query time; `daily_rollup` is not widened.
The seed list waits on the owner.

## 3. CI was red for a reason nobody was reading (BUG45, `04505ac`, `06cc187`)

Run 36870957475 (`537fb17`): the **screenshot** job failed for the first time
in twelve runs. The logs needed a GitHub sign-in; the owner signed `gh` in.

- It failed on `ReceiptCorpusTest.theCorpusIsReachableInCi`, not on a golden.
  Step 4 gave `:feature:ocr` goldens, so `verifyRoborazziSmsFullDebug` began
  running that module's unit tests, corpus gate included.
- Without `--continue`, Gradle stopped there. **Onboarding's 18 and settings'
  16 goldens had never been compared on Linux.** The red looked like the
  known corpus red.
- Found reading the unit-test jobs alongside: `:core:model:test`
  (`MoneyQuantityTest`, Law 3) never ran in CI. A plain JVM module is not part
  of the flavour aggregate; `preMergeCheck` ran it, CI did not.
- Fix: the screenshot job passes `-Pledgerflow.goldensOnly` (excludes the
  corpus test in `:feature:ocr`; the unit-test jobs still run it) and
  `--continue`; the unit-test jobs add `:core:model:test` and `--continue`.
- Regression test: a BUG45 section in `guard-workflows.sh`, red on each of the
  three ways it could regress, green on the fix.
- Run 36901318734 (`06cc187`): screenshot **green**, every one of the 13
  modules verified (ocr, onboarding and settings executed; the other eleven
  reused 537fb17's results for identical inputs). Unit-test jobs red only on
  `theCorpusIsReachableInCi`, with `:core:model:test` now among their tasks.

## 4. Step 5 — `targetSdk` 37

**Plan first** (CLAUDE.md §10). Android 17's behaviour changes, from Google's
two lists, mapped against the app: SPEC §3.2. One has teeth: a standard SMS
classified as an OTP is withheld three hours for a target-37 app, and the SMS
adapter only listens for the broadcast. Harmless for a real OTP; a loss only if
a transaction alert is misclassified. **SPEC §16 Q24**; the owner judged it no
real risk now or soon. Edge-to-edge and predictive back were already enforced
at 36, so SPEC §3's old claim that the bump "carries" them was struck.

**Testing route — owner's choice C (2026-10-01):** verify Android 17 behaviour
on the phone's own Android 17 update, not an emulator (a local API 37 image
was a partial download; CI's emulator job is blocked by the corpus).
`TESTING.md` **B7** is that pass. Nothing here claims an Android 17 result.

What was verified, on Android 16 and the dev box:

- **Gate:** `preMergeCheck --no-build-cache`, 3,429 tasks, green. Lint on both
  flavours; `EXPECTED_MERGED_PERMISSIONS` needed **no edit**, and all four
  pinned variants plus `restrictedPermissionCheck` pass. Library unit tests
  stayed up to date, correctly: their inputs do not include `targetSdk`.
- **R8:** `assembleSmsFullBenchmark`, `assembleSmsFullRelease` and
  `assemblePlaySafeRelease` build; every merged manifest reads
  `targetSdkVersion="37"`.
- **Goldens:** unchanged by construction (Robolectric renders at
  `GOLDEN_SDK` 34, not the target) and verified in the gate.
- **On SM-S721B (Android 16):** the real app installed over the top
  (`firstInstallTime` 2026-08-25 unchanged, now `targetSdk=37`), force-stopped
  and relaunched to Home, no Recovery. `:core:database` and `:core:data`
  instrumented, **392 tests, 0 failed**: the v1→v11 migration chain,
  `PreMigrationGuardTest`, `BackupRestoreRoundTripTest` (12),
  `AttachmentBackupRoundTripTest` (14) and `AttachmentBackupFaultTest`,
  `PurgeDeletedEntriesTest`, CSV round-trip. `Bug38_OcrWorksInAShrunkBuildTest`
  green against LF Bench rebuilt at 37 (release code, R8), which then opened on
  its seeded vault.
- **Not done:** LF PlaySafe was not reinstalled (D11 is running on it until
  about 10-08); no benchmark numbers were taken (nothing in the bump touches
  startup or the frame path).

## 5. State

| | |
|---|---|
| Gate | `preMergeCheck --no-build-cache` green at the bump; guards (schema, versionCode, workflows) pass |
| CI | `06cc187`: screenshot green over all 13 modules; unit-test jobs red only on the corpus gate (owner-only blocker); `instrumented`/`assemble` still never run |
| Pushed | through `06cc187` |
| Committed, not pushed | the `targetSdk` 37 bump and this log |
| Device | `com.ledgerflow.debug` (real vault) and LF Bench at target 37, installed over the top. LF PlaySafe untouched, still at 36, running D11 |

## 6. Outstanding

1. **P5 step 6:** Play listing and the playSafe release track; the owner does
   the console side.
2. **TESTING B7** on the phone's Android 17 update, after B1. Until then no
   Android 17 behaviour is verified, Q24 included.
3. **Owner decisions:** V2-PLAN D1–D8; the merchant-category seed list
   (V2-PLAN §5.2); ghost Inbox fixtures (BUG-B: Google Pay 6/6 weak, SBI YONO
   3/3, HDFCBK 4/33 per Diagnostics); the 120 Hz bar and a second test phone;
   contrast as an instrumented check, or H4 by hand.
4. **CI corpus blocker** (private corpus repo and token). `ubuntu-latest`
   moves to Ubuntu 26 on 2026-10-19.
5. **Owner TESTING rows:** H2, H4, D5, D9b, D11's result.
6. Carried from step 4: the draft row's "Draft" marker ellipsizes at 2.0;
   Export's file name breaks mid-date at 2.0; Entry's SaveBar padding (measure
   on device first). `CsvWriter`'s two decimals (v2).
