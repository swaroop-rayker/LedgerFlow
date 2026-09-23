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

A daily `PeriodicWorkRequest` — **aimed at 03:00 by each pass since the
2026-09-23 amendment below**; as first built it drifted to the hour of its last
run. It requires **battery not low** and no network of any kind. Each run: open
the vault through `openForBackgroundWork()` (ADR-0026's rule, no new key material), seal the `.lfbk`, verify as in (b), promote, seal any
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

### Amended 2026-09-23 — aimed at 03:00, by the owner (§8 BUG31)

**What was wrong.** "A daily `PeriodicWorkRequest`" is not nightly. WorkManager
counts a period from the moment the previous run *finished*, so the pass kept
the hour of its last run — on the owner's phone about 21:50 — and had no
relationship to night at all. Two nights of checking for an overnight backup
found none, and none was due. Nothing had failed. The schedule meant something
other than what every screen said.

**The choice.** The owner was offered renaming it to a daily backup or aiming
it at a fixed early-morning hour, and chose the hour: **03:00 local.**

**How it holds the hour — decided with the owner, four points:**

| | Question | Chosen |
|---|---|---|
| 1 | The hour | **03:00** on the device's clock. |
| 2 | How it stays anchored | **A periodic request, re-aimed by every pass**, using `PeriodicWorkRequest.Builder.setNextScheduleTimeOverride` (WorkManager 2.9+; the project is on 2.11.2). Each pass first calls `updateWork` with the next 03:00, and then backs up. |
| 3 | Migrating an install with the old schedule | **A new unique name** (`nightly-backup-0300`) enqueued with `KEEP`, and the old name (`nightly-backup`) **cancelled on every cold start**. |
| 4 | What the screens say | **"Nightly", unchanged, and no hour.** Now that it is aimed at night, the word is true. The hour lives here and in `SPEC.md` §5.9, beside the statement that Android decides. |

**Why a re-aimed periodic request, not a chain of one-time requests.** Both
were on the table. A chain holds the hour, but it is only as durable as its
weakest run: a pass that dies before it enqueues its successor ends the
backups, silently, until the app is next opened. The periodic request cannot
be lost. Its only failure is drift, and the override removes that. The override
moves only the *next* run, so every pass must set it again. Two WorkManager
behaviours make that safe. Both were read from the 2.11.2 bytecode rather than
assumed, and both are held by `Bug31_NightlyBackupStaysAnchoredTest` against a
real in-memory WorkManager:

- `updateWork` on a request that is **running** does not stop it. The scheduler
  cancel is skipped while the Processor holds the work, and the result is
  `APPLIED_FOR_NEXT_RUN`.
- The update **bumps the override's generation**, and the end-of-run reset
  clears the override only when the generation is unchanged. A pass that
  re-aims itself therefore keeps its aim. Without that, the reset would undo it.

**Re-aimed first, not last.** The pass is the part that can die (this ADR's
previous amendment is about exactly that). Aimed before the backup starts, a
pass that never finishes has already pointed its successor at 03:00. If the
re-aim itself fails, the next run follows this one by a day and the run after
it is back on the hour. **The cost of a failure is one drifted night, never the
schedule.** A reboot needs nothing from us: WorkManager re-registers pending
work from its own boot receiver (`RECEIVE_BOOT_COMPLETED`, already pinned in
`EXPECTED_MERGED_PERMISSIONS`), so no new permission merges.

**Why `KEEP` and a new name, not `UPDATE`.** `KEEP` alone would leave every
existing install on the drifting request forever. `UPDATE` on each cold start
would re-aim the pass whenever the app is opened. That is harmless before 03:00
and wrong after it: opening the app at 06:00, while Doze is still holding the
03:00 pass, would push it to tomorrow — a skipped night for looking at the
phone. RollupWorker's KDoc records the same trap. A new name with `KEEP` sets
the aim once. The pass keeps it from then on. Cancelling the old name is
idempotent, so it needs no "migrated" flag that could itself be lost or
restored wrong.

**DST, decided rather than inherited.** Where a spring-forward gap swallows
03:00 (Europe/Helsinki jumps from 03:00 to 04:00), the aim moves forward by the
gap, to 04:00 that morning, and the night is kept. Where autumn repeats 03:00,
the aim is the first one, and a pass after it aims at tomorrow, never at the
second 03:00 an hour later. One night never writes two backups and rotates a
good one out. `BackupTimeTest` pins both against Helsinki. The owner's IST has
neither case, which is why the test does not use it. A change of time zone
moves the aim at the next pass: the pending run keeps the instant it was given.

**What Android still decides, and what is therefore promised.** 03:00 is the
**earliest** the pass may start, not when it starts:
- **Doze** holds it to the next maintenance window, and those windows grow
  hours apart through the night.
- **App standby** can defer it further for an app that is rarely opened.
- **The battery constraint** skips a night when the battery is low.

Picking the phone up ends Doze, so in practice the pass runs between 03:00 and
the morning. **The screens promise "nightly" and nothing more precise**, and a
night the pass could not run shows up where it always did: the record in
`app_meta` and, after seven days, Home's reminder.

**Cost, stated.** One more AndroidX test artifact, `androidx.work:work-testing`
(test scope only, version by reference to the runtime). No production
dependency, permission, schema or key material changes. The worker gains an
injected `Clock`, and ten lines that await a WorkManager future.

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
- **Worker tests**: skips without a folder or enrolment, rotates to five,
  records the date only after the (b) check passes. (As first written this said
  "retries on failure"; the 2026-09-22 amendment removed the retry.)
- **The schedule (2026-09-23 amendment):** `BackupTimeTest` (JVM) — the next
  03:00 across a day boundary and a year end, the device's zone deciding the
  morning, and Europe/Helsinki's gap and overlap.
  `Bug31_NightlyBackupStaysAnchoredTest` (Robolectric, real WorkManager) — a
  fresh schedule aims at 03:00; a pass held until 06:40 aims its successor at
  03:00, not at 06:40 tomorrow, and keeps the battery constraint; a failed pass
  still re-aims; a pass the system stops mid-backup has already re-aimed (the
  "first, not last" rule); opening the app while a pass is overdue does not
  push it to tomorrow; the drifting schedule is cancelled. Ten mutations, each
  red on its own set of cases (`SPEC.md` §8 BUG31).
- `TESTING.md` gains a row: leave the phone overnight, confirm a backup appeared
  with no words typed, then restore it on the playSafe install.
