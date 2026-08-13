# ADR-0009 — Key rotation: two distinct procedures, not one

- **Status:** Accepted
- **Date:** 2026-08-13
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none
- **Spec sections touched:** `SPEC.md` §7.2, §7.7, §5.9

## Context

The spec had no answer for "the recovery phrase was exposed" or "the user wants a new one". A recovery factor that can never be changed is a recovery factor that stays compromised forever, so this is a genuine gap rather than a nicety.

The kickoff proposed a single procedure — backup, new DB with new key, verify, swap, delete old — and flagged that `PRAGMA rekey` is not crash-atomic.

**That framing conflates two different operations**, and separating them is most of the value of this ADR:

| | What leaked | What actually has to change |
|---|---|---|
| **Phrase rotation** | the 24 words (KEK-B) | the *wrap* of the DEK. The DEK itself is untouched. |
| **DEK rotation** | the DEK, or the database file with it | every page of the database. |

The key hierarchy in §7.2 exists precisely so these are separable: the DEK is wrapped independently by each factor. Rotating a factor means rewriting a small wrapped-blob file. It does **not** mean rewriting the database — that is only necessary if the DEK itself is suspect.

Treating every rotation as a full database rewrite would make the common case (user wants new words) a multi-second, storage-hungry, crash-sensitive operation for no security benefit.

## Options considered

### Option A — `PRAGMA rekey` in place

Rejected outright. It rewrites every page in place; a process death or battery pull midway leaves a database encrypted under two different keys with no way to tell which pages are which, and no rollback. It is the exact failure shape BUG8 exists to prevent. Not usable regardless of the scenario.

### Option B — Sidecar rebuild via `sqlcipher_export()`

SQLCipher's own supported mechanism:

```sql
ATTACH DATABASE 'rotate.tmp' AS rotated KEY '<new key>';
SELECT sqlcipher_export('rotated');
DETACH DATABASE rotated;
```

The new database is built as a separate file. The live database is untouched until an explicit rename. A crash at any point before the rename leaves the original intact and a discardable temp file behind.

### Option C — Round-trip through the `.lfbk` restore path

Back up under the old key, then restore under the new one, reusing the §5.9 container and the existing restore machinery. Attractive because it exercises code that is already the most heavily tested path in the app (it is the P0 exit gate). Slower — full encrypt plus full decrypt — and it forces the rotation to pass through a file on disk containing the entire database, which is a larger exposure window than an ATTACHed sibling.

## Decision

**Two procedures, selected by what was actually compromised.**

### 1. Phrase rotation (the common case) — re-wrap only, no database rewrite

```
1. Generate a new 24-word mnemonic; confirm via the same word challenge
   as onboarding (3 random positions, no skip — §7.4).
2. Unwrap the DEK using the CURRENT factor (Keystore or old phrase).
3. Derive new KEK-B from the new mnemonic (§7.2 pinned derivation).
4. Write wrapped_dek_phrase.bin.tmp -> fsync -> verify by unwrapping it
   back to a DEK equal to the live one -> atomic rename.
5. Bump app_meta.dekWrapVersion.
6. Write a fresh .lfbk under the NEW phrase and verify it.
7. Offer the new Recovery Kit, and tell the user plainly that older
   backups still open with the old words (see below).
```

Milliseconds, not seconds. Step 4's verify-before-rename is the same discipline as the backup writer: a wrap that has not been round-trip unwrapped is not a wrap.

**The consequence that must be surfaced to the user, not buried:** `.lfbk` files are encrypted with a phrase-derived key (§5.9), so **rotating the phrase cannot retroactively protect backups that already exist.** Any copy already in Drive, a chat, or on a USB stick remains decryptable with the old words forever. Rotation protects future backups only. The rotation flow therefore ends with an explicit screen listing where backups are known to have been written and instructing the user to destroy them. Silently rotating and letting the user assume they are safe would be worse than not offering rotation at all.

### 2. DEK rotation (device compromise) — sidecar rebuild, Option B

Used when the DEK or the database file itself is suspect.

```
1. Verified .lfbk snapshot under the current phrase.        <- rollback point
2. Generate a new DEK. Wrap it under KEK-A and KEK-B.
3. ATTACH a new file keyed by the new DEK; SELECT sqlcipher_export();
   DETACH. The live database is untouched throughout.
4. Verify the sidecar: open it under the new DEK, PRAGMA integrity_check,
   PRAGMA foreign_key_check, canary, and row-count equality per table.
5. Atomic swap: rename live -> .rotating.old, sidecar -> live.
6. Commit the new wrapped-blob files; bump dekWrapVersion.
7. Reopen the live DB successfully, THEN delete .rotating.old.
```

Everything before step 5 is discardable; everything after is idempotent on retry. A crash leaves either the old database or the new one, never a hybrid. On next launch, a `.rotating.old` alongside a healthy live database means step 5 succeeded and cleanup did not — resume at step 7. A `.rotating.old` with an unopenable live database means the swap was interrupted — roll back by renaming.

Option C was rejected as the primary mechanism because writing the whole plaintext-equivalent database into a single file widens the exposure window during an operation whose entire premise is that something has been compromised. It survives as the fallback if `sqlcipher_export()` fails.

## Consequences

**What this makes easy.** The common case is cheap enough to offer freely in Settings rather than hiding behind a scary warning. Phrase rotation touches one small file and cannot corrupt the database, because it never opens it for writing.

**What this makes hard.** Two procedures mean two flows, two tests, and a triage question in the UI ("were your words exposed, or your device?"). The wording of that question matters and must not be jargon — users do not know what a DEK is. The flow asks about observable events, not key material.

**What we now have to maintain forever.** Crash-resumption logic keyed on `.rotating.old`, and its startup handling. The `dekWrapVersion` contract. A rotation entry in `TESTING.md`, since crash-during-swap is not something CI reproduces convincingly.

**What would make us revisit this.** If `sqlcipher_export()` proves slow enough on a large database to need a foreground service and progress UI, DEK rotation should adopt §8.1's Upgrading-screen pattern rather than growing its own.

## Verification

- `Rotation_PhraseRotation_PreservesAllData` — rotate, then unlock with the new words only, asserting row-level equality.
- `Rotation_OldPhraseRejectedAfterRotation` — the old mnemonic no longer unwraps.
- `Rotation_DekRotation_SurvivesCrashBeforeSwap` / `...AfterSwap` — kill the process at each side of step 5 and assert the database is intact and openable in both cases.
- `Rotation_OldBackupStillOpensWithOldPhrase` — asserts the documented, deliberately-not-fixed behaviour, so that it can never become an accidental regression claim.
