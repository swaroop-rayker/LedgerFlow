# Session log — S15 (2026-09-19): the owner's device rows, BUG29 and BUG30

Starts at `b04c83b` (four commits not pushed: `0dc99f2`, `00d9715`, `75d7d38`,
`b04c83b`). Schema **v11, unchanged**.

## 1. Owner device rows

### D6c — five kept, nothing else touched: **pass**

Run in `/sdcard/Ledgerflow_Backups_old` (a local folder, so `adb` could list
it; Drive cannot be listed). Rotation reads only names
(`DefaultBackupRepository.rotate`: `ledgerflow-YYYYMMDD-HHMMSS.lfbk`, sorted,
newest four kept besides the one just written), so with the owner's agreement
the folder was **padded with four name-stamped copies** of a real backup
(`ledgerflow-20260918-00000{1..4}.lfbk`) and three dummy files
(`d6c-unrelated-1.txt`, `d6c-unrelated-2.lfbk.bak`, `notes.pdf`, checksums
recorded) instead of six backups with 24 words each.

| Step | Folder after | App said |
|---|---|---|
| Owner's first backup here (`115506`, before padding) | 1 real + 1 new | — |
| Padding | 6 backups | — |
| Run A (`120020`) | five newest; `-000001` and `-000002` gone | "2 older backups removed; the five newest are kept." |
| Run B (`120253`) | five newest; `-000003` gone | "1 older backup removed; …" |

The dummy files kept their checksums throughout, no `.tmp` was ever left, and
`attachments/` stayed. The padding and dummies were removed afterwards; the
folder holds four real backups. Backups now go to this local folder, not Drive —
the owner's to change back.

**What padding does not test:** six real writes in a row. Rotation never opens a
file, so nothing it decides was left unexercised.

### D10 — restore onto a clean install: **pass**, on the `playSafe` build

This phone reports `pm get-max-users` = 1 (no guest or secondary user; Secure
Folder, user 150, is closed to `adb`). The clean install was the **other
flavour**, `com.ledgerflow.playsafe.debug` — a separate app with empty data, the
same restore code, the same backup folder. `TESTING.md` D10 now says so.

| Case | Result |
|---|---|
| A phrase failing its checksum (typed by accident) | stopped before any key work; nothing written |
| Wrong valid phrase (public BIP-39 vector, `abandon`×23 `art`) | "…not the one this backup was made with. Nothing was restored." No database, no wrap, no marker |
| Folder route, owner's words | "291 records restored"; More: "Last backup 19 Sept 2026"; Back up now names the folder; force-stop → Home, no prompt |
| Single file (Android's picker), after `pm clear` of the test package only | "291 records restored"; Back up now **asks for a folder**; force-stop → Home |

**Not checked:** the "N receipt images weren't found" line — the vault has no
images, and the message only prints for N > 0.

The folder route was run by accident as the "single file" case: the restore
screen still showed the folder from an earlier attempt (BUG30), and tapping a
backup in the app's own list is the folder route. Read from the code before
calling it a bug; the single-file case was then run properly.

### D11 — the reminder: not yet

The rule is strictly more than 7 days (`BackupReminder.of`), so it can first
appear on **2026-09-26** after the time of day of the last backup.

## 2. BUG29 and BUG30 (found running D10)

**BUG29** — back with the keyboard open left the restore screen and the words.
Diagnosed from the system log (`CoreBackPreview`, `ImeTracker`): the conditional
`BackHandler` re-registered above the keyboard's callback after each attempt.
Fixed in two layers plus a keyboard close on Restore; back during a restore now
waits. **BUG30** — the three phrase screens outside the nav graph hold their
ViewModels on the activity, so the words outlived them: restore after leaving,
Recovery after a *successful* unlock, onboarding after setup. Each now forgets
explicitly and keeps the words on failure. SPEC §8 has both rows;
`CLAUDE.md` §7 gains the rule.

## 3. What the method caught

- **A device test that passed on the bug.** The first BUG29 test was green on
  the unfixed code; the next identical run was red. The keyboard reports visible
  before its back callback is registered, so an attempt inside that window put
  the keyboard on top by luck. A 1.5 s settle made it red 3/3 unfixed and green
  3/3 fixed. Also: a test tap never raised the keyboard on this Samsung
  (`mImeWindowVis=0`), so the test asks the insets controller directly.
- **A test that could never pass.** The sweep's baseline was red on
  `leavingWhileARestoreRuns_changesNothing`: the folder listing is asynchronous
  and the test submitted before it landed, so no restore ever started. Every
  JVM row carried that red; J2, whose only red it was, was re-run after the fix.
- **Wiring no test saw.** `Left` was first sent from `RestoreRoute`, which no
  test composes; moved into the screen, where the device test asserts it.
- **Detekt failed the uncached gate** (`LongMethod` on `RestoreScreen`, one long
  test line). The back handling moved into `RestoreBackHandler`,
  `rememberCloseKeyboard` and `leavingVia` rather than a suppression, and the
  mutations whose anchors moved (D1, D3, D5) were re-run with the same results.
  The gate was then finished with `preMergeCheck --no-build-cache` (no
  `--rerun-tasks`): **3183 tasks, 147 executed, 3036 up-to-date from the
  uncached run, none from cache.** Guards: schema, versionCode, corpus-order pass.
- **A PowerShell harness that printed nothing** and exited 1 — replaced by a
  bash one that reports a harness error when no result file appears.
- **The phrase in a screen dump.** The phrase chips' accessibility labels
  ("Word 1, …") carry the words, so `uiautomator` reads them. The dump that
  caught a mistyped (checksum-failing) phrase was deleted at once, and every
  later dump on a phrase screen filtered `Word N` labels out. **Open for the
  owner:** any accessibility service can read the phrase that way; it is also
  what TalkBack needs.

## 4. Outstanding

1. **Push**: the four S14 commits plus this session's — not yet authorised.
2. **The playSafe test copy** holds a full copy of the owner's data: uninstall
   or keep (owner's call).
3. **D11** from 2026-09-26.
4. **Backup UX**: options written up in `docs/BACKUP-OPTIONS.md` for a later
   decision (option E, Android's own backup, recommended for a first look).
5. The phrase chips' accessibility labels (above).
6. Owner-only, unchanged: the 25 Aug Recovery Kit `.txt` is still in
   `Download/`; the private corpus repo and CI token.
7. Carried from S14 §5: item 2's gaps (older-schema restore fixture, image-pass
   crash, `AttachmentBackup` fault injection, `SafBackupFolder`), and the
   feature designs (step 20, Recovery's restore entry and type-DELETE, transfers,
   phrase rotation, ADR-0025 part 2).
