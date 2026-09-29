# Session log — S17 (2026-09-23 → 27): nightly backups made to happen at night, CI revived, and the phrase kept private

Starts after `f3a049c` (S16's log). `317d0b2` (2026-09-22, late) belongs here,
not to S16. Schema **v11, unchanged** throughout. §7 adds what happened from
2026-09-27 evening to 2026-09-29, so that stretch is written down somewhere.

## 1. The nightly backup, three times over (ADR-0027, amended 09-22, -23, -27)

S16 shipped the pass. This session found that its schedule was wrong in three
separate ways, each found from a real phone and none of them by a test.

- **It failed and then wedged, and nothing could say why** (`317d0b2`). The
  first scheduled pass failed three times and stuck in `RUNNING`. That state
  schedules no system job, so it would never have run again unless the app was
  opened. Every attempt now leaves a record in `app_meta` (skip, failure or
  success, with its time). A failed pass waits for tomorrow instead of retrying,
  and "Back up now" names the date of a failed automatic pass, and only then.
  Why those three writes failed is still unknown. The record exists to answer
  that next time.
- **BUG31: "nightly" had no relationship to night** (`09c2496`). A periodic
  request counts from when the previous run *finished*, so the pass kept the
  hour of the last run: about 21:50 on the owner's phone. Every pass now aims
  its successor at the next 03:00 local, under a new unique name
  (`nightly-backup-0300`). DST gaps and overlaps are handled, and the screens
  say "nightly" without naming an hour.
- **BUG37: a pass killed mid-way lost the whole night** (`c5af95a`). BUG31
  re-aimed *first*, on the theory that a dying pass should already point at
  tomorrow. That was backwards: a killed pass had already said "tomorrow", so
  nothing retried it. It now re-aims *last*. A killed pass keeps tonight's aim
  and runs again the same night, capped at three interrupted attempts. It was
  found from the idle playSafe copy's WorkManager record, with no log at all.

**How a night is now checked without a cable.** The owner cannot leave the
laptop connected overnight, and a log capture dies with the Claude session that
started it. WorkManager's own database (`no_backup/androidx.work.workdb`) holds
`period_count`, `run_attempt_count`, `stop_reason` and the next aim. It is read
over USB in the morning (memory: `read-workmanager-record-not-overnight-logs`).
`6837d20` makes debug builds log WorkManager at DEBUG for the rare case that
needs more. **Backup file names are UTC.** Judge a night by the file's modified
time (`f983656`, TESTING D12).

**Nights on the owner's phone since BUG31**, by modified time (IST): 09-24
03:49, 09-25 04:27, 09-26 04:48, 09-27 03:19, 09-28 03:54, 09-29 07:57. The
last two were read in WorkManager's record: completed, `run_attempt_count` 0.
The earlier nights' attempt counts were not recorded. The 07:57 is the phone holding all
background work: LF PlaySafe's pass ran within five seconds of it. BUG37's
same-night retry has not yet been exercised on the device.

## 2. CI: two weeks of running nothing (BUG32, BUG33)

**BUG32** (`3408d8c`). A scripted edit on 2026-09-08 wrote a `\n` into
`ci.yml` as a real newline, which ended a `run: |` block mid-script. GitHub
does not load a workflow it cannot parse. Each push got a run that "failed" in
zero seconds with **no jobs**, which looks like ordinary red CI. The last runs
that executed anything were on 2026-08-29. `scripts/guard-workflows.sh` now
parses every workflow. It is a **pre-push** guard, because a check inside
`ci.yml` cannot see `ci.yml` break, and it fails when it has no PyYAML rather
than passing. The same `\n` slip happened twice more while this was being fixed,
both in heredoc'd scripts (memory: `scripted-edits-mangle-backslashes`).

**BUG33** (`859f50f`), from the first run that executed jobs again:
- **Screenshots.** 17 of 30 goldens failed on anti-aliasing: at most 4/255 per
  channel, from Windows-recorded goldens drawn with Linux fonts.
  `LfScreenshotOptions` tolerates 8/255 and nothing more. A one-pixel shift, a
  palette change or one pixel past the bound still fails.
- **The `.cacheDir` grep.** It caught the root build script that defines the
  ban, and had failed on every run since 2026-08-14.

**Node 24** (`d344d00`): every action moved off the deprecated Node 20 runtime.
Breaking changes were read at each major version boundary. `runs-on` stays
`ubuntu-latest`, so the move to Ubuntu 26 on 2026-10-19 will be noticed rather
than deferred.

**Still red:** both unit-test jobs, believed to fail only on
`ReceiptCorpusTest.theCorpusIsReachableInCi` (not confirmed for the latest
runs). While they fail, `instrumented` and `assemble` have never run on GitHub.
Unblocking them needs the private receipt-corpus repo and a CI token, which
only the owner can set up.

## 3. The Recovery Kit and its QR code (ADR-0028, amended 09-26)

- **BUG35** (`8e3c208`, reported by the owner): the scanner's only way back sat
  under the navigation bar. The controls are now one card inset by system bars
  and cutout. At font scale 2.0 the label clipped (BUG9), so it became "Type the
  words". Measured on the device: button bottom 1949, nav bar from 2196.
- **BUG34** (`8e3c208`, found by reading the code while fixing BUG35): after a
  foreign QR code, the scanner stopped delivering anything while the camera
  stayed live. It now hands every *distinct* code to the screen once. Checked
  on the device with a Wi-Fi QR code followed by a kit's code.
- **A kit can be saved after onboarding** (`8e3c208`): "Back up now" offers one
  after a successful backup. That is the one moment the app holds words it has
  proved are this vault's. It is also the only route to a QR kit for an install
  that onboarded before kits carried one.
- **Onboarding saves the PDF by default** (`6ca1b42`). The pinned button used
  to save the text file, which cannot carry a code.
- **BUG36** (`51231d2`, reported by the owner): the QR code sat on the "How to
  restore" lines. `RecoveryKitLayout` wraps the steps and puts the code below
  the last one. The device test finds the code by its finder patterns and
  requires no dark pixel beside it.

## 4. Phrase privacy (`12ee296`)

Owner's decision, 2026-09-27: treat the phrase as sensitive data, with no
screen sharing. `phraseSecret()` sets `isSensitiveData` (TalkBack still reads
the words; other accessibility services and dump tools get an empty node) and
`sensitiveContent()` (Android 15+ blanks the window when the screen is shared
or recorded). It is applied to every node that carries a word, including the
scanner. The human half is TESTING D9b.

## 5. Two stated gaps closed, both finding nothing broken

- **Older backups restore** (`5951f60`). v1, v7 and v9 payloads, in the exact
  JSON shape each version wrote (taken from `BackupPayload`'s history), restore
  into v11 with what the migrations would have given them. CLAUDE.md gains the
  rule: a new column gets a payload field whose default equals its migration's.
- **Bad receipt copies are caught** (`d671f50`). A flipped or truncated image is
  `failed`, never `written`, and is not left in the folder, for both the phrase
  writer and the nightly writer. The next pass writes it again.

## 6. What the discipline caught, and what it got wrong

- **A test that read the constant it tests** let a mutation of BUG37's cap
  (three to one) pass. It now states the owner's number instead.
- **A mutation sweep that restored files by moving a backup back** kept the old
  timestamp, and Kotlin's incremental compiler kept the mutant. The sweep now
  rewrites the bytes.
- **Test results misread.** Two "green" readings on 2026-09-27 came from bugs
  in the script parsing the test XML, not from the tests. The specifics were not
  written down at the time.
- **Case-insensitive log filters:** "BUILD" matched "build-logic". Use
  `-CaseSensitive`.

## 7. After the range (2026-09-27 evening → 29)

- **"There is no option to save a Recovery Kit"** was a false alarm. The window
  that was open was the throwaway playSafe copy, and both debug flavours showed
  as "LedgerFlow". `19528a2` labels the playSafe debug build **"LF PlaySafe"**.
  Release builds keep "LedgerFlow". In the real app the offer appeared after a
  backup, as designed.
- **The Compose stability report now exists** (`6a64b2b`). CI had passed the
  property since 2026-08-13, but nothing read it. On `:core:designsystem`: two
  unstable classes (`MoneyFormat`, `LfIcons`), and every restartable composable
  is skippable.
- **TESTING D11** (`9f2a5f1`) runs on a throwaway install whose backup folder
  was deleted. Every nightly pass records `lastBackupAt`, so the 7-day reminder
  can never appear on an install that backs up nightly.
- **`lf-rollup-cold-fill` left as `RUNNING`** (09-29) was stale, not stuck. The
  process had died mid-run on the third attempt. Woken in the background, it
  finished in 3.8 s, most of it opening the vault in a cold process. No change
  was made.

## 8. State

| | |
|---|---|
| Gate | Each commit: `preMergeCheck --no-build-cache` (uncached with `--rerun-tasks` for build-logic changes and `12ee296`), 3183 tasks |
| Guards | schema, versionCode, corpus-order, workflows: pass |
| Pushed | everything through `9f2a5f1` |
| Device | real app `com.ledgerflow.debug` from `6a64b2b`'s tree, vault intact. LF PlaySafe: throwaway vault onboarded 2026-09-29 16:27, no backup folder (D13 running) |

## 9. Outstanding

1. **D13 tonight's result** (LF PlaySafe): the pass should complete
   (`period_count` 2) with no file, no notification and no error.
2. **D11** from 2026-09-30: one backup into a throwaway folder, delete it, and
   read Home after 8 days.
3. **Owner rows:** D5 (open the kit's PDF, scan its QR), D9b (TalkBack reads the
   chips; recordings blank phrase screens).
4. **CI's corpus blocker** (repo + token, owner-only). Ubuntu 26 from 10-19.
5. **Unstable classes in the stability report**: all modules are unread except
   `:core:designsystem`.
6. Carried: phrase rotation must also replace the sealing key (ADR-0009,
   ADR-0027); the Recovery screen's restore entry and §7.3's type-DELETE
   dialog; a "transfer" concept (Q22); SPEC §16 Q3/Q16/Q19;
   `docs/BACKUP-OPTIONS.md`; `SafBackupFolder` has no automated test.
