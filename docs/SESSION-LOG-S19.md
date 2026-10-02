# Session log — S19 (2026-10-01 → 10-02): P5 steps 4–6 — every screen has a golden, CI tells the truth, target 37, two stores

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

## 5. Step 6 — the stores (`2b99ee6`, `de60801`, `4fd37bb`, and the icon commit)

**BUG46, found by preparing to ship.** `release.yml` had never run, and could
not have shipped: release had no `signingConfig` (the `keystore.properties` it
wrote was read by nothing, so every APK was `-unsigned`), its durability step
named `connected*ReleaseAndroidTest` tasks that do not exist, and both
flavours' `mapping.txt` were copied to one name. Fixed with
`-Pledgerflow.requireReleaseSigning`, `:app:verifyReleaseSigning<Variant>`
(apksig for APKs, the JAR signature for the AAB, the debug key rejected), a
dry run of every `release.yml` task name in CI's static analysis, and a
BUG46 section in `guard-workflows.sh`. Proved with a throwaway key, deleted
after: green when signed, red on the debug key and when unsigned.

**Google Play** (owner, 2026-10-02): playSafe as `com.ledgerflow.playsafe`, one
key (smsFull's signer and Play's upload key, Google holding the app signing
key), ML Kit's usage reporting **kept and declared**, a new personal account
(internal testing, then a 12-tester 14-day closed test). Every arm64 native
library is 16 KB-aligned. `docs/play/`: listing, Data safety, declarations,
privacy policy, the owner's sequence.

**Samsung Galaxy Store** (owner, same day): smsFull as `com.ledgerflow`, the
GitHub release's package and key, so neither store updates the other's
install. The universal APK is 196.8 MB, about 130 MB of it x86 code, so Galaxy
gets the arm64 split (43.1 MB). AGP refuses to build an AAB with ABI splits on,
found by running it, so `release.yml` builds in two runs. Free to publish, but
Samsung requires commercial seller status (D-U-N-S or business documents) even
for free apps. `docs/galaxy/`.

**The icon** (owner chose "two books" of three original concepts): debit and
credit pages side by side, never touching, in the app's own colours. Adaptive,
with a themed-icon layer; the app had shipped with Android's default icon until
now. Store PNGs rendered from the same path data
(`docs/brand/render_store_graphics.py`), text in the bundled Inter (OFL).

**Screenshots**, six, from LF Bench, cropped to the app's own area (1080 × 2104;
the full frame's 2.17 : 1 is over Play's limit). LF Bench was reseeded first: its
merchants were real brands drawn against random categories ("Ola · Shopping"),
which would have put other companies' trademarks on the listing. Now generic
made-up names, each filed under its own category; 1,927 entries.

**What writing the listings corrected** (each checked against the code):
- No allowlist editor shipped, so the notification list is described as
  built in (editor deferred to v2).
- **There is no app lock.** The repository has no biometric code; §7.6 was
  never built (V2-PLAN §3.5 says so). The listing briefly claimed one, on the
  strength of a fingerprint prompt seen on the owner's phone, which is the
  phone's, not the app's. Memory corrected.
- Every incoming SMS is stored encrypted on the phone; only bank senders'
  are parsed; bodies are purged after 90 days (D-09). "Other messages are
  ignored" was not true and is not what the policy says.

## 6. State

| | |
|---|---|
| Gate | `preMergeCheck --no-build-cache` green at each code commit; guards (schema, versionCode, workflows) pass |
| CI | `4fd37bb`: guards, static analysis (with the release dry run), screenshot and compose stability green; unit-test jobs red only on the corpus gate (owner-only blocker); `instrumented`/`assemble` still never run |
| Pushed | through `4fd37bb`; the icon and screenshot commit and this log await the owner's go-ahead |
| Device | `com.ledgerflow.debug` (real vault, target 37, new icon), installed over the top, `firstInstallTime` 2026-08-25 unchanged. LF Bench reseeded (generic merchants, 1,927 entries). LF PlaySafe untouched, still at 36, running D11 |

## 7. Outstanding

1. **Before the first upload (owner):** make the release key and set the four
   GitHub secrets (`docs/play/README.md` §1–2); choose the public contact
   email (deferred, probably v2 — both stores need one to publish); a
   trademark check of the name and icon. Then the `v0.1.0` tag.
2. **Galaxy Store:** commercial seller status (D-U-N-S or business documents).
   If review rejects `RECEIVE_SMS`, the fallback is a playSafe build under
   `com.ledgerflow.galaxy`, not built.
3. **TESTING B7** on the phone's Android 17 update, after B1; A7 and A8 on the
   first store installs.
4. **Owner decisions:** V2-PLAN D1–D8; the merchant-category seed list
   (V2-PLAN §5.2); ghost Inbox fixtures (BUG-B); the 120 Hz bar and a second
   test phone; contrast as an instrumented check, or H4 by hand.
5. **CI corpus blocker** (private corpus repo and token). `ubuntu-latest`
   moves to Ubuntu 26 on 2026-10-19.
6. **Owner TESTING rows:** H2, H4, D5, D9b, D11's result.
7. **v2 now also holds:** the notification allowlist editor and the app lock
   (§7.6, never built). Carried from step 4: the draft row's "Draft" marker
   ellipsizes at 2.0; Export's file name breaks mid-date at 2.0; Entry's
   SaveBar padding (measure on device first). `CsvWriter`'s two decimals.
