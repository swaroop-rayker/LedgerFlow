# ADR-0033 — Restore from Settings replaces the vault, and keeps the one it replaced until it is not needed

- **Status:** Accepted (owner, 2026-11-05)
- **Date:** 2026-11-05
- **Deciders:** Swaroop (owner, chose each option below), lead engineer
- **Supersedes:** **ADR-0026 decision (c)**, "refuse to restore over an existing
  vault". The rest of ADR-0026 stands: the backup's own phrase protects the
  restored vault, the backup is decrypted before anything is written, the
  marker file, the one-transaction import.
- **Spec sections touched:** `SPEC.md` §5.9, §7.3, §9.3; `docs/V2-PLAN.md` §3.6;
  `TESTING.md` D14; `CLAUDE.md` §7 (`:core:crypto`)

## Context

V2-4 (`docs/V2-PLAN.md` §3.6) makes Restore reachable from Settings. Until now
it existed only at first run, and ADR-0026 (c) had it **refuse** whenever a
vault existed, so that restoring could never destroy anything. A restore from
Settings is, by definition, a restore over a vault: the phone has a ledger, and
the user wants the backup's instead.

That makes it the second deliberate destruction in the app, after the purge of
deleted entries, and §7.3's "start fresh only behind a type-the-word-DELETE
dialog" is the existing rule for exactly this. Every choice below was put to the
owner with options before any code was written.

## Decisions (owner, 2026-11-05)

| | Question | Chosen |
|---|---|---|
| a | Where | A **Restore from a backup** row on Profile > Data, below Back up now. It opens the same restore screen as first run, worded for a phone that has a ledger. |
| b | When to confirm | **After the words, before anything is written.** The backup is decrypted and parsed in memory first, so wrong words, a damaged file and a newer format are answered with nothing touched. Only a backup that opens reaches the confirmation. |
| c | What the confirmation says | A page of its own (mockup A of three): the backup's entries and this phone's, each book counted separately and the Inbox beside them, that anything added since the backup is lost, and a field where the user types DELETE (§7.3). The button is Replace. |
| d | Which words protect the restored vault | **The backup's**, as at first run (ADR-0026 b). The page checks whether they are this phone's words too, read-only (`DekManager.verifyPhrase`); if not, it says that this phone's Recovery Kit stops opening the ledger. |
| e | The vault being replaced | **Set aside, not deleted.** Database, wraps and receipt images are moved, by rename, into `filesDir/<db>.replaced/`. If the restore fails in-process they are put back at once and the old vault reopens. If the process dies, the interrupted-restore screen offers **Put back my ledger** beside finishing the restore. The set-aside copy is deleted on the first launch that opens the restored vault cleanly. Like the pre-migration snapshot (ADR-0019) it is never called a backup, and nothing says it can be got back later. |
| f | The Keystore key | **Kept.** The new DEK is wrapped under the phone's existing Keystore key, and a new key is made only if that one will not seal. So the set-aside vault's Keystore wrap still opens: putting it back needs no words. First run and Recovery still make a new key, unchanged. |
| g | Capture during the restore | Paused by the marker, as for an interrupted first-run restore: background work cannot open the vault while it exists. A message that arrives in those seconds is a capture gap and shows on Home (BUG50). |
| h | Afterwards | "Open my ledger" lands on Home with nothing of the old screens kept: the shell is rebuilt for the new vault, and the once-per-vault housekeeping (seeding, the draft sweep, a parse pass) runs again for it. |

## The order of operations

`VaultSession.replaceFromBackup`, under the session's mutex, from an open vault:

1. **Decrypt and parse the backup in memory.** Any refusal returns here and
   nothing has changed.
2. **The marker**, fsynced, with content `replace-pending v1`. From here a crash
   lands on `RestoreInterrupted`, as in ADR-0026.
3. **Close the vault**, and remove its WAL checkpoint observer: left registered,
   its next `onStop` would reopen the file at the path the restored vault is
   being built at. The WAL and SHM files are moved with the database.
4. **Set aside.** The database's files, the key directory and the image
   directory are each renamed into the set-aside directory, then a
   `set-aside-complete` file is fsynced. Every move is idempotent (move only if
   the source exists and the destination does not), so a resumed restore
   finishes the set-aside before anything new is written — the old wrap is
   never mistaken for an interrupted restore's. Until the flag exists nothing of
   the restore's own has been written, which is what lets a put-back tell the
   two apart.
5. **The DEK**: a fresh one under the backup's words, wrapped first under the
   phrase and verified, then under the **existing** Keystore key (f).
6. **The rows**, as ADR-0026: one transaction, canary verified.
7. On failure at 5 or 6: **put back** (below), reopen the old vault, and report
   that nothing changed.

Then, as ADR-0026: the images, the marker removed, the report, and "Open my
ledger" hands the vault over.

**Putting back**: each part that is aside is moved back over whatever is in its
place. A part that is not aside is left alone while the set-aside is
unfinished (it never moved, so it is the old vault's), and removed once the
flag exists (it is the restore's: the old vault had none). Nothing aside at
all — the process died between the marker and the first move — means the
vault never left. Then the marker and the set-aside directory go, and the vault
opens as on any launch, through the Keystore.
Nothing the user owned is deleted by a put-back: what is removed was written
from the backup minutes earlier, and the backup is still where it was.

## Consequences

- **Two vaults on the phone for a while**: from the replace until the next
  launch. Setting aside is a rename and costs no space, so unlike the migration
  snapshot there is no storage check before it; the restored vault needs the
  space a first-run restore needs.
- **The words may change.** A backup made under other words leaves the phone
  protected by those. The confirmation says so before DELETE, in those terms.
- **Anything added after the backup is gone** once the set-aside is deleted.
  The confirmation states the backup's date and both sides' counts; it does not
  try to say which entries differ.
- `:core:crypto` gains one option, `keepKeystoreKey`, on `initialize` and on the
  restore's resumed unlock. The phrase derivation, the wrap formats and the
  blob order (phrase first) are unchanged.
- The once-per-process housekeeping in `AppViewModel` becomes once per vault
  handed to the app (`VaultRepository.generation`).
- Offering restore from the Recovery screen (§7.3 step 3) is still not built.

## Verification

- **`ReplaceFromBackupTest`** (instrumented, real vaults): a replace returns
  every row of the backup and none of the phone's, under the backup's words, and
  reopens silently after a relaunch; the set-aside copy exists until that clean
  launch and is gone after it; wrong words, a damaged file and a newer schema
  change nothing; a failure after the set-aside puts the old vault back and it
  opens with no words; a restore interrupted after the set-aside routes to the
  interrupted screen, keeps background work out, and either finishes with the
  backup's words or puts back the old vault, which opens with no words; an
  interrupted set-aside is completed, not mistaken for an old wrap, or put
  back; one interrupted before the first move is put back with no words.
- `DekManager` tests: with `keepKeystoreKey` the existing key seals the new DEK
  and still opens the old wrap; without it, a new key as before; a key that will
  not seal is replaced.
- `RestoreFromBackupTest`, `BackupRestoreRoundTripTest` and
  `LedgerSingleWriterTest` unchanged and green.
- `RestoreViewModelTest` for the confirm step: Replace stays off until DELETE is
  typed exactly; the words are cleared on success and on leaving.
- `TESTING.md` D14, on the phone.
