# ADR-0025 — Backups are manual ("Back up now"), and nightly backups wait for public-key sealing

- **Status:** Part 1 **Accepted** (manual backup, built). Part 2 **Proposed** (public-key
  nightly backups — not built). **Owner, 2026-09-19: keep part 2 proposed and build
  it later;** manual backups plus a Dashboard reminder (no backup, or none in 7
  days) are the interim answer.
- **Date:** 2026-09-18
- **Deciders:** Swaroop (owner, chose among options A–D in `SPEC.md` §16 Q23), lead engineer
- **Supersedes / Superseded by:** none. **Closes `SPEC.md` §16 Q23.** Amends §5.9's
  trigger column and BUG4(c).
- **Spec sections touched:** `SPEC.md` §5.9, §8 (BUG4, BUG25), §16 Q23; `TESTING.md` D6–D9

## Context

Nothing in the app wrote or restored a backup. `DatabaseBackupManager.writeBackup`
had no production caller; onboarding took a SAF tree grant and nothing ever
wrote to it. §5.9 specified a nightly `PeriodicWorkRequest`, and it could not be
built: a `.lfbk` is sealed under a key derived from the 24-word phrase, and the
app never holds the phrase after onboarding (ADR-0011). That is the wall
ADR-0019 already hit for the pre-migration snapshot. The app was promising a
durability it did not have.

## Options (as put to the owner)

| | Option | Why not / why |
|---|---|---|
| A | **Manual "Back up now" that asks for the 24 words each time** | No new key material; nothing on the phone can ever decrypt a backup. Cost: no automatic backups; typing 24 words each time. |
| B | Store a backup key so a worker can seal unattended | A key on the device that decrypts every backup: the third wrap ADR-0011 and `CLAUDE.md` §7 forbid. |
| C | **Seal backups to a public key derived from the phrase** | Nightly backups with nothing on the phone that can read one. Cost: new cryptography in §7's danger zone, a new container version, likely a dependency, one-time phrase entry on existing installs. |
| D | Hold the phrase in memory for a session | B's objection with a shorter fuse; the phrase on the heap is worse than the DEK already is. |

## Decision

### Part 1 — accepted and built: A

"Back up now" in Settings. The order of its steps is the design, and each is
tested (`BackUpNowTest`, `BackupNowViewModelTest`):

1. The checksum first (`BackUpNowUseCase`), so a typo never reaches the key
   derivation (`CLAUDE.md` §7).
2. **The words must open this vault** — `DekManager.verifyPhrase`, read-only.
   Every valid BIP-39 phrase passes the checksum; a backup sealed under the wrong
   one would report success and never restore. This step was not in the plan the
   owner approved and was added because without it option A is unsafe.
3. The folder's grant is still held, else the screen asks for a folder.
4. The `.lfbk` is written through `BackupFolder.writeVerified`: temp, flush, the
   bytes that landed decrypted and parsed, then promoted.
5. Only then: rotation to the newest five (always keeping the one just written)
   and `lastBackupAt`. A failed backup deletes nothing and records nothing.
6. The receipt images (ADR-0023), with their own counts.

The words live in the screen's ViewModel while it is open, so one wrong word is
fixable, and are cleared on success and when the screen closes. They are never
persisted. The field that takes them no longer lets the keyboard learn them
(BUG25) — found while building this, and fixed first.

### Part 2 — proposed, not decided: C, as the route back to nightly backups

Sketch, for the owner to accept, amend or reject:

- At a moment the phrase is present (onboarding; once on existing installs, via
  this same screen), derive an **X25519 key pair** from the seed with a new,
  versioned HKDF `info` string. Store **only the public key** on the device.
- A nightly worker seals each backup to that public key (an ephemeral-static
  ECDH → HKDF → AES-256-GCM construction, e.g. HPKE base mode). Only the phrase
  regenerates the private key, so the phone still holds nothing that can read a
  backup.
- `.lfbk` `formatVersion` 2, with the KEM output in the authenticated header.
  Version 1 stays readable forever.

What must be settled before any of it is built: **the primitive's source**
(platform X25519 needs API 31; `minSdk` is 26, so this is likely a dependency,
and ADR-0010 rejected Tink on size); **golden vectors from an independent
implementation**, as `AttachmentBackupKey`'s were; and whether the images
(ADR-0023) move to the same scheme. Until this part is accepted, backups are
exactly as frequent as the user makes them.

## Consequences

- **Honest now.** The Settings row says "No backup yet. Your data exists only on
  this phone." until a verified backup exists, then names its date.
- **§5.9's nightly promise is struck, not deferred**, and BUG4(c)'s "`BackupWorker`
  failure posts a persistent notification" has nothing to attach to. What
  replaces it is the row's date; a Dashboard reminder when that date is old is a
  natural next step and is not built.
- **Found on the owner's device:** the backup screen asked for a folder, so the
  onboarding grant was absent or had lapsed. No backup could ever have been
  written to it; the first backup starts with the picker.
- **Restore was still missing** (§16 Q11) when this was written. **Closed by
  ADR-0026:** a first-run restore from onboarding, under the backup's own phrase.
- `SafBackupFolder` has no automated test: a real tree grant only comes from the
  system picker. `TESTING.md` D6 is its check, on local and cloud providers.
