# ADR-0035 — A receipt leaves the phone only when Google Drive holds a checked copy

- **Status:** **Accepted** (owner, 2026-11-08). Build order: this ADR, then the
  Receipts page with BUG88, then schema v14 and "Remove from phone", then
  removal by age.
- **Date:** 2026-11-08
- **Deciders:** Swaroop (owner, chose option A and each decision below), lead engineer
- **Supersedes:** **ADR-0023's "No auto-purge"** paragraph (*Retention — Q5
  answered*). The rest of ADR-0023 stands: where images live, the two seals,
  the ≤1600px stored frame, the sidecars beside the `.lfbk`.
- **Amends:** **ADR-0034 §5**, the sentence "A sidecar is deleted from Drive
  when the phone's folder no longer has it" (the new rule is §4 below).
- **Settles:** `docs/V2-PLAN.md` decision **D5**.
- **Spec sections touched (as each PR lands):** `SPEC.md` §5.3, §5.9, §6.1,
  §9.3, §16 Q5; `docs/V2-PLAN.md` §1 (D5), §3.7; `CLAUDE.md` §7; `TESTING.md`
  (new rows).

## Context

V2-PLAN §3.7 asks for receipts to be deleted automatically after a period.
ADR-0023 declined a timed purge, and D5's recommendation was to allow it only
for receipts "already in a verified backup, never the only copy". Before
deciding, the code was checked for what "in a verified backup" can mean
(2026-11-08, `main` at `71d08cc7`):

- **Nothing records, per receipt, that it is backed up.** `AttachmentBackup`
  returns counts. `app_meta` keeps the last night's counts and Drive's last
  copy time, and `lastBackupAt` is written *before* the receipts are sealed.
- **"Verified" differs by path.** "Back up now" decrypts what it wrote. The
  nightly pass checks bytes and header only, because the phone cannot open what
  it seals (ADR-0027 b). Drive compares MD5 at upload, and later passes skip a
  file by name.

Four findings then shaped the decision:

1. **Deleting a receipt's row loses the receipt within five backups.** A
   restore looks only for images whose rows are in the `.lfbk`. A sidecar with
   no row is never read, and once the five backups that still list the row have
   rotated away, nothing can bring it back.
2. **Nothing on the phone can open a backup copy.** Each sidecar is sealed to
   the phrase-derived key (ADR-0027). A receipt that is no longer on the phone
   can be seen again only after a restore with the 24 words.
3. **Drive mirrors the phone's folder.** ADR-0034 §5 deletes a sidecar from
   Drive when the phone's folder lacks it. If a receipt has left the phone and
   the user then picks a new backup folder, the nightly pass has no local file
   to copy into it, and the next Drive pass would delete the last copy.
4. **A backup folder on the phone saves no space.** Its copy is about the same
   size as the local one, and after removal it would be the only copy, on the
   same phone. Only Drive is certainly off the phone.

Two more facts bear on the design: no screen yet shows a receipt image (nothing
calls `AttachmentRepository.read`), and today's "Delete all" removes the rows
and the local files but leaves every sealed copy in the backup folder and on
Drive, where finding 1 makes them unusable (BUG88).

## Options considered

| | | Verdict |
|---|---|---|
| **A** | Remove from the phone only with a **checked Drive copy**, checked at the moment of removal; keep the row | **Chosen** |
| B | As A, but a copy in the phone's backup folder is enough | Finding 4: no space saved, a single copy on the same phone |
| C | Each backup pass records per receipt that it was backed up (schema columns) | A claim about a folder and an account the user controls; it goes stale when the folder changes or a Drive file is deleted on the web, so removal would check again anyway |
| D | Decline; select-and-delete plus a reminder | Keeps ADR-0023, but doesn't give back the space |

## Decisions (owner, 2026-11-08)

| | Question | Chosen |
|---|---|---|
| a | Which option | **A** |
| b | Removal by age | **Off until the user turns it on.** The choices are 30, 90 and 365 days; **90** is preselected |
| c | A manual "Remove from phone" | **Yes**, beside Delete, offered first as the less destructive choice |
| d | Recording a removal | **Schema v14**: `attachment.removed_from_phone_at`, nullable |
| e | BUG88 | **Fixed**: Delete also removes the backup-folder copy, and Drive's copy follows |
| f | PR order | This ADR → Receipts page (with BUG88) → v14 and "Remove from phone" → removal by age |

## Design

Two words are used apart throughout, in the code and on screen:

- **Remove from phone.** The local file goes; the row, the backup-folder copy
  and the Drive copy stay. A restore with the 24 words brings it back.
- **Delete.** The row, the local file and the backup-folder copy go, and Drive's
  copy goes on its next pass. Nothing brings it back.

### 1. What counts as a checked Drive copy

For one receipt, all of the following, evaluated **in the same pass that
removes it**, never from a stored claim:

1. Google Drive backup is on, and the pass holds a token for its account.
2. The phone's backup folder is reachable and holds `attachments/<id>.lfba`
   whose header is sealed to this install's nightly key
   (`LfbaContainer.sealedTo`, ADR-0027).
3. Drive's "LedgerFlow backups/attachments/" lists a file of that name whose
   `md5Checksum` equals the MD5 of the folder file's bytes, read in this pass.

That makes two copies off the app's private storage, one of them off the
phone, both sealed to the words. It cannot prove the copy decrypts: no writer
without the phrase can (ADR-0027 b). As with the nightly backup, **nothing on
screen says "verified"**; it says "in your Google Drive backup".

### 2. Which receipts can leave the phone

- **Never a receipt whose Inbox candidate is still pending.** Its image is what
  the user reviews.
- **Removal by age** takes only receipts attached to an entry, and counts age
  from `attachment.created_at`, which is when the receipt was captured.
- **Manual removal** takes any selected receipt that passes §1. A receipt that
  fails the check stays, and the result names how many stayed and why (Drive
  off, offline, not yet copied).

### 3. Recording it: schema v14

`attachment.removed_from_phone_at INTEGER` (nullable, epoch millis), through a
`Migration_13_14` built as `CREATE new / INSERT SELECT / DROP / RENAME`, with a
`MigrationTest`, the committed schema JSON, and a `BackupPayload` field that
defaults to `null`, which is what the migration writes (`CLAUDE.md` §5 Room).
The migration is shown to the owner before it is built.

- **Order on removal:** the column is set, then the file is unlinked. A failed
  unlink leaves a file that `read` still shows, and the next pass retries it.
- **A screen showing a removed receipt** says it is in the Google Drive backup
  and that restoring with the 24 words brings it back. It does not offer to
  fetch it, because the phone cannot open the copy (finding 2).
- **A restore brings back every receipt it finds** and clears the column for
  each one it restores. Durability comes before space: on a new phone the
  nightly pass then copies them into the new folder, and removal by age, if
  on, removes them again once they pass §1.
- **The space line** counts files on disk, as today, and names the removed
  receipts separately.

### 4. Drive keeps a copy while anything still needs it (amends ADR-0034 §5)

A sidecar is deleted from Drive only when **the phone's folder does not have it
and no `attachment` row in the vault names it**. The current rule tests only
the folder (finding 3). Testing only the rows would be wrong too: after a
restore of an older backup, newer receipts' sidecars are still listed by the
newer backups on Drive, and only the folder knows about them.

The phone's backup folder keeps its rule: **no pass ever removes a sidecar from
it.** Only Delete does (§5).

### 5. Delete (BUG88)

Delete removes the row, the local file and, when the backup folder is
reachable, its copy there. Drive's copy then meets neither condition of §4 and
goes on the next Drive pass. The five backups still listing the row report the
image as not found if one of them is restored, which is ADR-0023's honest
sentence rather than a silent gap. When the folder cannot be reached, the
confirmation says that its copy stays.

Delete is irreversible, so `CLAUDE.md` §7's rules apply: a `Warning`
confirmation naming the count and the size, and "Remove from phone" offered
before it wherever both apply. **No dialog offers to back up first**: "Back up
now" needs the 24 words, and the app never holds them.

### 6. Removal by age

- **Where:** a step at the end of a Drive pass whose outcome is `Copied`, in
  the same worker, after rotation. A failed, partial or signed-out pass removes
  nothing.
- **The setting** lives in `app_meta` (`receiptsRemoveAfterDays`, absent means
  off), because the worker opens the vault anyway, as with ADR-0034's status.
- **The record:** each run writes when it ran and how many receipts and bytes
  it removed to `app_meta`, so the Receipts page can say what happened while
  nobody was looking (ADR-0027's amendment of 2026-09-22).
- **Turning it on** goes through a confirmation that says, in plain words, that
  removed receipts can be seen again only after a restore with the 24 words.
- **Turning Drive off** stops removal. The Drive page says how many receipts
  now exist only in backups, and that deleting them on Drive's website deletes
  them.

## As built (2026-11-08, "Remove from phone")

- The check is `RemoveReceiptsFromPhoneUseCase` in `:core:domain`. It reaches
  Drive only through the `:core:drive` ports, and the backup folder through
  `ReceiptBackupCopies` in `:core:data`. It finds Drive's folders and never
  creates them.
- The Inbox rule is enforced in the `UPDATE` statement itself
  (`ReceiptDao.markRemovedFromPhone`), so no caller can skip it.
- The Drive copy pass keeps a sidecar if the folder has it or any receipt row
  names it (`ReceiptsRepository.idsIn(All)`).
- The nightly pass reports a removed receipt with no folder copy as
  `removedFromPhone`, not as unreadable.
- On the owner's phone, the backup folder chosen in the system picker is itself
  a Google Drive folder. That is a second off-phone copy, and the rule is the
  same.

## Consequences

- **Gained:** receipts stop growing without limit on the phone, without
  deleting the only copy of any of them.
- **Given up:** day-to-day access. A removed receipt is invisible until a
  restore, and that is said before the user turns removal on.
- **Risk:** a user who deletes the "LedgerFlow backups" folder on Drive's
  website loses removed receipts. The Drive page says so, and nothing the app
  does can prevent it.
- **Cost of a check:** one folder read and one MD5 per receipt considered, plus
  one Drive listing per pass. A run is proportional to the receipts that have
  just reached the age, not to all of them.
- **Tests (named as built):**
  - the §1 check, one test per condition that fails it;
  - a pending candidate's receipt is never removed;
  - the Drive keep rule: folder changed after a removal; restore of an older
    backup;
  - `Bug88_DeleteRemovesTheBackupCopyTest`;
  - the v14 migration and an older-schema backup restoring with the new field
    null;
  - a restore clears `removed_from_phone_at` for each receipt it brings back;
  - a `TESTING.md` row against a real Drive account on the SM-S721B.
