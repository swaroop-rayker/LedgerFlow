# ADR-0026 — Restore happens at first run, under the backup's own phrase

- **Status:** Accepted
- **Date:** 2026-09-18
- **Deciders:** Swaroop (owner, chose each option below), lead engineer
- **Supersedes / Superseded by:** none. **Closes `SPEC.md` §16 Q11.** Implements §7.3
  step 3's "restore from a `.lfbk`" half; the type-DELETE "start fresh" half is not
  part of this.
- **Spec sections touched:** `SPEC.md` §5.9, §7.3, §7.4, §16 Q11; `TESTING.md` D10

## Context

Backups could be written and verified (ADR-0025) and none could be restored:
`DatabaseBackupManager.restore` and `AttachmentBackup.restoreAll` were tested and
had no production caller. A user reinstalling after a factory reset has no wrap
and no database, so launch routes them to onboarding, which issues a **new**
phrase. Nothing offered "I already have a backup".

Restore touches the key path (`CLAUDE.md` §7), so every choice below was put to
the owner with options before any code was written.

## Decisions

| | Question | Options | Chosen |
|---|---|---|---|
| a | Where does restore live? | Onboarding's first screen; the Recovery screen; Settings | **A quiet link on onboarding's first screen**, below the currency list — not a second primary action. Before the currency choice, because the backup carries its own. |
| b | Which phrase protects the restored vault? | The backup's; a new one (two phrases); the backup's then a forced rotation | **The backup's.** A fresh DEK is wrapped under the words that opened the backup (`DekManager.initialize`, phrase first and verified, then Keystore). One phrase, the one the user already holds. Rotation (ADR-0009) is not built. |
| c | Restoring over existing data? | Refuse; replace behind a type-DELETE gate | **Refuse.** Restore replaces onboarding and never a vault, so it never destroys anything. `AlreadySetUp` if a wrap or an open database exists and no restore is pending. |
| d | How is the backup chosen? | Folder then list; single file only; folder only | **Folder, then list** (`OpenDocumentTree`, newest `ledgerflow-*.lfbk` preselected) — only a tree grant reaches `attachments/` beside the `.lfbk`, and the grant becomes this install's backup folder. **Plus "Use a single file"** for a `.lfbk` that travelled alone: rows come back, and every image is reported as not found (ADR-0023's sentence). |
| e | A crash part-way through? | A marker row in `app_meta`; a marker **file** written before the wrap; "wrap last" | **A file, `filesDir/<db>.restore-pending`, fsynced before the wrap.** An `app_meta` row cannot exist before the database does, and the wrap is written first — so a crash between the two would leave a wrap and no rows, which the ordinary launch opens, finds no canary in, and sends to Recovery with the wrong sentence. "Wrap last" needed the DEK before its wrap existed and a cleanup that deletes a database file. |
| f | What of the old phone's `app_meta`? | Keep the backup's with corrections; also reset `lastBackupAt` | **Keep the backup's**, except `schemaVersion` (this build's) and `backupTreeUri` (the folder just picked, or removed for a single file) — both set **inside the import's transaction**. `lastBackupAt` stays: that backup exists. |

## The order of operations

`VaultSession.restoreFromBackup`, all under the session's mutex:

1. Refuse if a vault exists and no marker does (c).
2. **Decrypt and parse the backup in memory** (`DatabaseBackupManager.open`).
   Wrong words, a damaged file and a newer schema are answered here, and **nothing
   has been written** — no marker, no wrap, no database. `keyCheck` separates the
   first two (§5.9).
3. Write the marker (e).
4. The DEK: `initialize(words)` on a first attempt; on a resumed one where a wrap
   already exists, the DEK those words unwrap — **different words are refused**
   (`NotTheInterruptedRestoresPhrase`), because the wrap is the vault's phrase now.
5. Create the database, write the canary, import every row in **one transaction**
   with the `app_meta` corrections inside it (f). A resumed restore whose rows
   already committed is recognised (the database holds a base currency, which
   before the import it never does) and is not imported twice.
6. The images, then the marker is removed. A crash during the image pass returns
   to the restore, which recognises the committed rows and runs the images again.
7. The vault stays `Working` — open, not yet the app's — until the user has read
   the report and taps "Open my ledger".

While the marker exists, launch routes to `VaultState.RestoreInterrupted` and
**nothing opens the vault**: `openForBackgroundWork` returns null, so an SMS
captured mid-restore cannot land rows that collide with the ones still to come,
and `AppViewModel`'s seeding waits for `Unlocked` as it always has.

## Consequences

- **One phrase, and it is not re-shown or re-challenged.** Typing all 24 words
  and having them open the backup is stronger proof of possession than the 3-word
  challenge. The Recovery Kit and folder steps are skipped for the same reason.
- **A phrase the user suspects is exposed stays in use** until rotation exists.
- **Someone who onboarded on the new phone first cannot restore** without
  clearing the app's data in Android settings. That vault is new and near-empty,
  but the app does not offer to delete it: the only destruction here stays
  behind a gate that does not yet exist (§7.3's type-DELETE).
- **An interrupted restore has no way back to onboarding.** If a wrap was
  written, only its words finish the job; there is nothing to lose by clearing
  the app's data, and the screen does not pretend otherwise.
- The single-file route leaves no backup folder chosen; "Back up now" asks for one.
- A backup from an **older** schema imports through the payload's defaults, which
  no test yet exercises with a real old file.

## Verification

- **`RestoreFromBackupTest`** (instrumented, real vaults, a second key directory
  and Keystore alias as the "new phone"): the whole restore returns every row
  and image under the backup's words, and no other words open it; a relaunch
  opens it silently; wrong words, a damaged file, a newer schema and a lost
  folder each write nothing; an existing vault is refused and untouched; a
  restore interrupted after the wrap routes back, keeps background capture out,
  and only its words finish it; one interrupted after the rows committed is not
  imported twice; a single file restores rows and counts every image as not found.
- **`RestoreViewModelTest`**, **`RestoreMessagesTest`**, **`RestoreFromBackupUseCaseTest`**,
  **`BackupNamesTest`**, and `AppViewModelTest`'s route.
- `BackupRestoreRoundTripTest` and `LedgerSingleWriterTest` unchanged and green: the
  import still only inserts rows from a decrypted backup, because
  `OpenedBackup.Ready` can only be constructed by `DatabaseBackupManager.open`.
- **Mutation-swept on the device**, one rule at a time against `RestoreFromBackupTest`:
  no refusal of an existing vault; the marker written before the decrypt; launch
  ignoring the marker; a new DEK even when a wrap exists; re-importing committed
  rows; the old phone's folder kept; the marker never removed; no image pass; the
  vault handed to the app before the report — each reddens the test that names
  it. **Two stay green, and are stated rather than implied:** resetting
  `schemaVersion` (every test backup is this schema's, so the value is already
  right — it waits for an older-schema fixture) and writing the canary before
  the import (every backup already carries it; kept as defence in depth). On the
  JVM: the ViewModel's "a finished restore is final" guard, the use case's
  checksum-first rule and BUG26 each redden their own test.
- `TESTING.md` D10 — the end-to-end check on a clean install, which is the
  owner's to run: it needs the 24 words and a profile without a live vault.
