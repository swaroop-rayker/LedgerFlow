# Session log — S16 (2026-09-20/21): backups that run themselves, and a phrase you can scan

Starts at `3ab647c` (S15, pushed). Schema **v11, unchanged** — neither feature
touched the schema, because the one new stored value is an `app_meta` key.

## 1. Why this was built

The owner asked why Google and Meta never make anyone type 24 words. The answer
is that they hold a spare key behind an account and a rate-limited server, and
this app has neither — so the secret must stay a generated 256-bit value. What
follows from that is the whole session: **the secret cannot change, so change
when it is asked for (ADR-0027) and what carries it (ADR-0028).**

`docs/BACKUP-OPTIONS.md` (S15) held the options. The owner chose **C**.

## 2. ADR-0027 — nightly backups sealed to a phrase-derived public key

**The primitive was decided by measurement, and the first measurement changed
the design.** ADR-0025 part 2 assumed X25519; the SDK's own `api-versions.xml`
says its key specs arrive at **API 33** against `minSdk` 26. NIST P-256 has been
in the platform since API 1, and DHKEM(P-256, HKDF-SHA256) is a standard HPKE
mode — so no dependency, no `minSdk` move.

**The JCA has no scalar multiply**, so the public key is an ECDH against the
base point for `x` plus the curve equation for `y`. Probed on the device before
it was written down: on the curve, seal and open agree, 3 ms.

**Then the RFC's own vectors caught the design error.** The ADR said either
square root would do. True of the shared secret, false of the scheme: RFC 9180
mixes the *serialised* public key into `kem_context`, so the mirrored point
gives a self-consistent construction nobody else agrees with. Six of nine
vectors were red, and only the ones that touch no public key passed. The root is
now chosen by which candidate verifies an ECDSA signature made with the private
key — no curve arithmetic in this repository.

**Vectors come from two places, neither of them us:** the CFRG working group's
`test-vectors.json` (mode 0, kem_id 16) and a separate Python implementation
written from the RFC, itself checked against those vectors first. An earlier
attempt to read the vectors out of a **web fetch** of RFC 9180 was thrown away:
the fetched scalars did not produce the public keys printed beside them.

**Container v2** carries the KEM's `enc` in `kdfParams` — what `kdfId` and
`kdfParamsLen` were put there for in the first place — and derives `keyCheck`
from the public key, because the night's writer has no seed. v1 stays readable
forever. The `.lfba` sidecar takes the same treatment.

**Enrolment is a side effect of a manual backup**, at the one moment the words
are in hand and have just been proved against this vault. Only the public half
is stored. `NightlyBackupWorker` then runs daily, battery-not-low.

**What "verified" means at night is weaker, and is stated rather than implied**
(decision b): the bytes that landed are compared with the bytes that were
sealed, and the header must still read as sealed to this install's key. It
cannot prove the file decrypts — that needs the words, and a manual backup still
does it.

## 3. ADR-0028 — the phrase as a QR code

The Recovery Kit PDF gains a QR (`LFBK1:` and the words); Recovery, restore and
"Back up now" all offer to scan one; typing stays primary everywhere. ZXing core
draws and reads it, CameraX was already in the APK, and the permission pin does
not move. A foreign QR leaves the camera open; a newer kit says it is newer.

`PhraseQr`/`applyScan` is one implementation behind three screens, and scanned
words go through the same `PhraseEntry.paste` and the same BIP-39 validation
typed ones do.

## 4. What the discipline caught

- **The RFC vectors, above.** The clearest argument this repository has for
  golden vectors from somebody else's implementation.
- **A web fetch that invented numbers.** Checked because a scalar and its public
  key must agree; they did not.
- **Lint found a crash-in-waiting:** `BigInteger.TWO` is API 33. It would have
  failed on exactly the Android versions P-256 was chosen to keep.
- **A test whose premise was wrong, twice.** "The vault is closed" first
  produced a perfectly good backup — `openForBackgroundWork()` reopens from the
  Keystore, which is what lets the worker run at all — and then threw Room's
  cancellation when the database was closed under a live session. Both are
  recorded in the test's KDoc.
- **Norton again** (PKIX on Maven Central) for the one new dependency; the owner
  disabled HTTPS scanning, the jar cached, and it was turned back on.
- **The packaging flake moved module**: `:feature:onboarding`, whose test APK
  had just grown by CameraX and ZXing. Same shape, passed alone. The memory note
  is now about heavy test APKs rather than about `:feature:ocr`.
- **An ADR that over-claimed its own tests** — it named a class that does not
  exist and a camera test that was never written — corrected in its own commit
  rather than quietly.

## 5. State

| | |
|---|---|
| Gate | `preMergeCheck --rerun-tasks --no-build-cache` stopped at the packaging flake; finished with `--no-build-cache`: **3183 tasks, none from cache** |
| Guards | schema, versionCode, corpus-order pass |
| Device | installed 2026-09-21 19:09, data intact (413,696 B), relaunch 259 ms |
| Commits | `3df2cae` KEM, `244065c` ADR-0028, `16f79dc` container v2, `7b22130` nightly, `a0e63c2` QR + wording, `3f365ab` ADR correction |

## 6. Outstanding

1. **Push** — six commits, not yet authorised.
2. **Nobody has scanned a real kit yet.** No automated test can: instrumentation
   cannot point a lens at a page. `TESTING.md` **D5** is where a person checks
   the camera plumbing, and it is the next thing to run on the phone.
3. **D12/D13** (a backup appears overnight; a pass with nothing to do is quiet)
   need real nights, and D12 needs one manual backup first to enrol.
4. **D11** (the reminder) from 2026-09-26.
5. **Phrase rotation (ADR-0009) must now also replace the sealing key** —
   written into ADR-0027 and `SPEC.md` §7.7 so it cannot be forgotten when
   rotation is built.
6. Carried: the owner's Recovery Kit still in `Download/`; the private corpus
   repo and CI token; S15 §5's gaps and feature designs.
