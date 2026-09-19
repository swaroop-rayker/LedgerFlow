# Backup options — making backups seamless without weakening them

**Status:** notes for a later decision (owner, 2026-09-19). Nothing here is
accepted. Any of it changes the backup design in `CLAUDE.md` §7's danger zone
and needs an ADR the owner approves before code.

## The problem

"Back up now" (ADR-0025 part 1) asks for all 24 words on every backup, because
a `.lfbk` is sealed under a key derived from the phrase and the app never holds
the phrase (ADR-0011). It is honest and safe, and it is bad UX: backups happen
only as often as the user is willing to type 24 words. The Home reminder
(7 days) is the interim mitigation.

## How seamless production apps do it

The common trick: **the secret is asked for at restore, not at backup.** The
backup runs with something the phone already holds; the user proves who they
are once, on the new phone. What varies is what the phone holds.

The third-party descriptions below are general understanding of how these
products work, not verified against their current documentation — check before
relying on any detail.

| Model | Who uses it | How backup runs unattended | Why a short secret is safe | Fits LedgerFlow? |
|---|---|---|---|---|
| **Server holds the key behind a PIN** | WhatsApp encrypted backups, Apple iCloud Keychain, Google's Android backup | The backup key lives in HSMs on the provider's servers | The HSM rate-limits guesses and locks after a few wrong ones | **No** — no server, and Law 6 |
| **Company holds the keys** | Most expense and social apps | The server can decrypt | It is not end-to-end at all | **No** — the product's premise |
| **Phone stores the backup secret** | Signal local backups | The code shown once is kept on the device | A leaked backup file alone is useless; the phone is needed too | Possible — option B below |
| **Phone holds only a public key** | Some password managers and encrypted-mail apps, for sharing | Seal to a public key; only the secret recreates the private key | The phone cannot open any backup, even stolen | Possible — option C below |

## The options for LedgerFlow

### A — type the words each time (built, ADR-0025 part 1)

No new key material; nothing on the phone can ever decrypt a backup. The UX
cost is the whole problem.

### B — store the backup secret on the phone (Signal's model)

- The phone keeps a Keystore-wrapped secret that can seal backups. Because
  `backupKey` is derived per file (`HKDF(seed, container.salt)`, `SPEC.md`
  §7.2), what would have to be stored is the **BIP-39 seed itself** —
  equivalent to the 24 words: it also unwraps the DEK and opens **every past
  backup**, including ones taken before any future phrase rotation.
- A backup leaked on its own (Drive, a chat) stays useless: it also needs the
  unlocked phone, which can already read the live vault.
- Reverses a locked decision (ADR-0011; `CLAUDE.md` §7 "never add a third
  wrap") — needs a superseding ADR.
- **Cost: about 2 sessions.** No new cryptography, no container change:
  enrolment, Keystore-wrapped storage, nightly worker, failure notification.

### C — seal to a phrase-derived public key (ADR-0025 part 2, proposed)

- The words are typed **once** (onboarding; once on existing installs). An
  X25519 key pair is derived from the seed; only the **public** key is stored.
  A nightly worker seals each backup to it. Only the phrase regenerates the
  private key.
- As seamless as B, stricter than Signal: a stolen phone opens nothing.
- **Cost: about 4–5 sessions.**

| Part | Size |
|---|---|
| ADR with the decisions below | ½ session |
| Derivation and sealing (ephemeral-static ECDH → HKDF → AES-GCM, header as AAD); golden vectors from an independent implementation (e.g. Python `cryptography`) plus RFC 7748's | 1 |
| `.lfbk` `formatVersion` 2; restore reads v1 and v2 forever; round-trip test for both | 1 |
| Public key storage (an `app_meta` value, no schema change), onboarding step, one-time enrolment | ½ |
| Nightly worker: background vault open, SAF write, rotation, `lastBackupAt`, failure notification (BUG4(c) returns), Drive offline, battery constraints | 1 |
| Mutation sweeps, device rows (a real night on Drive, a failure), SPEC/TESTING | ½–1 |

**Decisions C needs first:**

1. **Primitive source.** Platform X25519 needs API 31; `minSdk` is 26.
   Raising `minSdk` to 31 avoids a dependency (the owner's phone is API 36);
   otherwise a library (ADR-0010 rejected Tink on size; BouncyCastle is large).
   Never hand-rolled. A throwaway probe must first confirm the platform lets a
   key pair be built from raw derived bytes on the target API levels.
2. **Verification is weakened, and must be decided, not drifted into.** Today
   every backup is decrypted and parsed before it counts (`CLAUDE.md` §7
   "Backup writer", BUG4(b)). Under C the phone *cannot* decrypt its own
   backup; it can only check the landed bytes against what it sealed and parse
   the header.
3. **Receipt images** (ADR-0023): same scheme, or left to the manual backup.
4. **Enrolment** for existing installs: the words once, on the Back up now screen.
5. **Phrase rotation** (ADR-0009) must replace the public key too, or nightly
   backups keep being sealed to the old phrase.

### D — hold the phrase in memory for a session

Rejected in ADR-0025: B's objection with a shorter fuse, and the phrase on the
heap.

### E — Android's own backup (to investigate, ~½ session)

Android's cloud backup is end-to-end encrypted with the screen-lock PIN, with
guesses rate-limited by Google's hardware, and runs by itself (typically at
night while charging). LedgerFlow disables it (`SPEC.md` §7.5) because on its
own it cannot carry the Keystore key to a new phone.

If the backup included the encrypted database **plus `wrapped_dek_phrase.bin`**,
a new phone would find no Keystore wrap, route to the Recovery screen, ask for
the 24 words **once**, and re-wrap — a restore with little new cryptography.
Open questions before recommending it:

- Is a live SQLCipher database (with WAL) copied consistently, or does it need
  a `BackupAgent` / checkpoint first?
- Android's 25 MB per-app backup quota, against the database plus images.
- On Samsung, does it go to Google or to Samsung Cloud, and with what
  encryption?
- Law 6 / ADR-0021: the OS uploads, not our code — does that count as "sending
  user data anywhere"? It needs an explicit decision, as ML Kit's `INTERNET` did.
- A restored database from an older schema then runs the migration chain on
  first open (§8.1) — covered in principle, not by a test with an old file.
- Less user visibility and control than a chosen folder; the `.lfbk` route
  stays alongside it.

## Recommendation (2026-09-19)

1. Investigate **E** first (half a session, probes only): if it holds, it gives
   most of the seamlessness for the least work.
2. **C** if the goal is the strongest guarantee — the only option where nothing
   on the phone can open a backup.
3. **B** is a proven, legitimate model with less work, at the cost of the
   phone holding a secret that opens every past backup.
