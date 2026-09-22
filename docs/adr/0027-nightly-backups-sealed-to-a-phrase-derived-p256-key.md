# ADR-0027 — Nightly backups, sealed to a phrase-derived P-256 public key

- **Status:** **Accepted** (owner, 2026-09-20). Build order: derivation and vectors, container v2, enrolment and worker, then the UX.
- **Date:** 2026-09-20
- **Deciders:** Swaroop (owner, chose option C and each decision below), lead engineer
- **Supersedes:** **ADR-0025 part 2**, which sketched the same idea with X25519.
  Part 1 (manual "Back up now") stands and keeps working.
- **Spec sections touched:** `SPEC.md` §5.9, §7.2, §7.7, §8 (BUG4(b), BUG4(c)), §16 Q23;
  `TESTING.md` D6–D11; `CLAUDE.md` §7.

## Context

Backups happen exactly as often as the user is willing to type 24 words
(ADR-0025 part 1), because a `.lfbk` is sealed under a key derived from the
phrase and the app never holds the phrase (ADR-0011). The owner asked for
backups that run by themselves without weakening that.

The one thing that must not change: **nothing on the phone can open a backup.**

## The primitive — decided on measurement, not preference

ADR-0025 part 2 assumed X25519. Measured against this project's constraints:

| Route | Verdict |
|---|---|
| X25519 from the platform | `XECPrivateKeySpec` and friends arrive at **API 33** (checked in the SDK's own `api-versions.xml`), not 31 as assumed. It would mean `minSdk` 26 → 33, dropping Android 8–12. |
| X25519 from a library | Keeps `minSdk` 26 at the cost of a crypto dependency on the key path. BouncyCastle is large; ADR-0010 already rejected Tink on size. |
| **NIST P-256 from the platform** | **Chosen.** `ECPrivateKeySpec`, `ECPublicKeySpec` and `KeyAgreement` have existed since API 1. No dependency, `minSdk` unchanged, and it is a standard HPKE mode: DHKEM(P-256, HKDF-SHA256). |

**The gap that made this need proving.** The JCA has no scalar-multiply call, so
a secret number derived from the phrase cannot be turned into a public key
directly. It can be done with the platform alone: an ECDH against the curve's
**base point** yields the public point's `x`, and `y` follows from the curve
equation (`p ≡ 3 mod 4`, so a modular square root is one `modPow`).

**Corrected while building this, by RFC 9180's own vectors.** The first draft of
this ADR said either root would do, because `P` and `−P` share an `x` and ECDH
returns `x`. That is true of the *shared secret* and false of the *scheme*: the
RFC mixes the **serialised public key** into `kem_context`, so the mirrored
point yields a self-consistent construction that no other implementation agrees
with. The vectors caught it — six of nine failed, and only the ones that do not
touch a public key encoding passed. The root is now chosen by asking the
platform: sign a fixed probe with the private key, and keep the candidate that
verifies it. One signature, at most two verifications, once per derivation, and
no curve arithmetic in our code. **This is the reason golden vectors from
somebody else's implementation are not a formality.**

**Probed on the owner's phone (SM-S721B, Android 16, 2026-09-20)** with a
throwaway test, since deriving the public key this way is the one step no
document promises: the recovered point is on the curve, sealing and opening
agree, the other root agrees too, and the derivation costs **3 ms**. The probe
was deleted; what replaces it is the golden vectors below.

## The scheme, pinned byte-for-byte

`seed` is the BIP-39 seed of §7.2, unchanged. Labels follow **RFC 9180**
(HPKE) for DHKEM(P-256, HKDF-SHA256), `suite_id = "KEM" || 0x0010`, so the
derivation and encapsulation layers have published vectors.

```
DeriveKeyPair   RFC 9180 §7.1.3 over ikm = seed:
                dkp_prk   = LabeledExtract("", "dkp_prk", seed)
                candidate = LabeledExpand(dkp_prk, "candidate", I2OSP(i,1), 32)
                sk        = OS2IP(candidate), retried while sk == 0 or sk >= order
                pk        = sk × G: x from ECDH(sk, G), y from the curve equation,
                            the root chosen by which candidate verifies an ECDSA
                            signature made with sk (the sign is load-bearing)
sealing         ephemeral (ekS, enc = uncompressed ekP, 65 bytes), fresh per file
                dh        = ECDH(ekS, pk)                       (32-byte x)
                kem_ctx   = enc || pk
                shared    = ExtractAndExpand(dh, kem_ctx)       (RFC 9180, 32 bytes)
backupKey       HKDF-SHA256(ikm = shared, salt = container.salt[16],
                            info = "lfbk-backup-v2", L = 32)
keyCheck        HKDF-SHA256(ikm = pk, salt = container.salt[16],
                            info = "lfbk-keycheck-v2", L = 4)
opening         sk from the phrase, dh = ECDH(sk, enc), then identical
```

- **`keyCheck` had to change.** v1's is derived from the seed, which the night's
  writer does not have. v2 derives it from the **public** key, which the writer
  has and the reader re-derives from the phrase — so it still separates "wrong
  words" from "damaged file" (§5.9's third property).
- **No salt of our own before `DeriveKeyPair`.** The phrase alone determines the
  key pair, so a backup needs nothing but the file and the 24 words — and the
  RFC's own vectors apply to that step.
- **Only the public key is stored**, as 65 uncompressed bytes in `app_meta`
  (`backupSealPublicKey`, `backupSealVersion`). No schema change: `app_meta` is
  key-value. It sits inside the encrypted database because everything does, not
  because it is secret.

### Container v2

`formatVersion = 2`, `kdfId = 2` ("HKDF-SHA256 / DHKEM-P256"), `kdfParams = enc`
(65 bytes), `kdfParamsLen = 65`. Every other field, the header-as-AAD rule and
the reader hardening are unchanged — which is what `kdfId` and `kdfParamsLen`
were put there for. **Version 1 stays readable forever**, and a v1 file still
needs the phrase exactly as today. The `.lfba` sidecar (ADR-0023) takes the same
treatment, one `enc` per file.

## Decisions the owner made (2026-09-20)

| | Question | Chosen |
|---|---|---|
| a | Primitive | **P-256 from the platform.** No dependency, `minSdk` stays 26. |
| b | What counts as verified, when the phone cannot read back what it sealed | **Bytes and header.** The writer re-reads the file it wrote, compares it byte-for-byte with what it sealed, parses the header and checks magic, version and `keyCheck`. It cannot confirm it decrypts, so **nothing in the UI says "verified"** — the row says a backup was written, and when. |
| c | Receipt images | **Sealed nightly too**, same scheme. The folder is the user's, so there is no quota. |
| d | Enrolment | **The next time "Back up now" is opened**, where the words are being typed anyway; at onboarding for new installs. |

## What runs, and when

A daily `PeriodicWorkRequest`. It requires **battery not low** and no network of
any kind. Each run: open the vault through `openForBackgroundWork()` (ADR-0026's
rule, no new key material), seal the `.lfbk`, verify as in (b), promote, seal any
image not already in the folder, rotate to the newest five, record the date.

It **skips silently** when there is no backup folder, when enrolment has not
happened, or while a restore is pending — each recorded as a reason, not an
error.

**Failure stays quiet, deliberately.** BUG4(c)'s persistent notification is
*not* reinstated: a run of bad nights shows up where the user already looks —
Home's reminder line, which is driven by the date and says "Last backup N days
ago". A notification for something the user cannot act on at 3 a.m. is noise.

### Amended 2026-09-22, after the first real night — by the owner

The first scheduled pass on the owner's phone **failed three times and then
wedged**, and nothing anywhere could say why. Two changes, both the owner's
call:

1. **Every attempt is recorded in the vault** — `lastNightlyBackupAt` and
   `lastNightlyBackupOutcome` in `app_meta`, written for a skip, a failure and
   a success alike. A backup that runs unattended has to be able to account for
   itself; without this, "it never ran" and "it ran and failed" are
   indistinguishable the next morning, and the phone's log has rotated by then.
   The one case that cannot be recorded is `VaultClosed`, for the same reason
   the pass skipped: there is no database to write it to. **"Back up now" shows
   a line when the last attempt failed** — and only then, because a screen that
   narrates every quiet success teaches the user to stop reading it.
2. **A failed pass no longer asks WorkManager to retry.** It reports success
   and waits for the next night. The retry is what produced the wedge: three
   attempts, then the process died mid-run, and the work sat in WorkManager's
   `RUNNING` state — which schedules no system job at all, so it would never
   have run again without the app being opened. The pass is daily; tomorrow is
   the retry, and the record says what happened in the meantime.

**What is still unknown, and stated rather than papered over:** why those three
writes failed. The log had rotated before it was looked at, which is exactly
the gap change 1 closes.

## Consequences

- **The phone can no longer prove a backup opens.** This is a real weakening of
  §7's backup-writer rule and of BUG4(b), accepted here rather than drifted
  into: the guarantee becomes "these exact bytes are on disk and parse as a
  LedgerFlow backup". `keyCheck` still proves the file is sealed to *this*
  install's key, which catches the worst case — backups that nobody's phrase
  opens. **Manual "Back up now" keeps the full decrypt-and-parse check**, so the
  strong guarantee is still available on demand.
- **A stolen or seized phone opens no backup**, including last night's. That is
  the property this whole design is for, and it is stronger than the manual
  scheme only in frequency, not in kind.
- **This is not a third wrap** (§7, ADR-0011). Nothing new wraps the DEK,
  nothing on the device can decrypt anything it could not decrypt before, and
  the phrase remains the only thing that opens a `.lfbk`.
- **Phrase rotation (ADR-0009) must re-derive and replace the stored public
  key**, or nightly backups would keep being sealed to the old phrase. Backups
  already written stay openable with the **old** words, which §7.7 already says
  in general and which the rotation screen must now say in particular.
- **A user who never enrols keeps today's behaviour** exactly.

## Verification

- **Golden vectors, committed, from two independent sources:** RFC 9180's own
  A.3 vectors for DHKEM(P-256, HKDF-SHA256) `DeriveKeyPair` and encap/decap, and
  a full-container vector generated by an independent Python implementation
  (`cryptography`), covering `backupKey`, `keyCheck` and a sealed file this
  build must open. As with §7.2's vectors, **a failure means the code is wrong**.
- **Round trip on the device**: seal without the phrase, then open with the
  phrase on a *fresh vault with a different DEK and key directory* — the shape
  `AttachmentBackupRoundTripTest` already uses.
- **A v1 backup still restores** (the existing tests, unchanged), and a v2
  backup restores through ADR-0026's first-run path.
- **The phone never opens what it sealed**: a test asserts the sealing side
  cannot derive the private key, i.e. that nothing but the phrase produces it.
- **Worker tests**: skips without a folder or enrolment, retries on failure,
  rotates to five, records the date only after the (b) check passes.
- `TESTING.md` gains a row: leave the phone overnight, confirm a backup appeared
  with no words typed, then restore it on the playSafe install.
