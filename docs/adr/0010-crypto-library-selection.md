# ADR-0010 — Crypto library selection: platform primitives, hand-rolled constructions

- **Status:** Accepted
- **Date:** 2026-08-13
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. Does **not** reopen ADR-0003 — the key-hierarchy *design* stays accepted; this is the implementation choice underneath it.
- **Spec sections touched:** `SPEC.md` §7.1, §7.2, §11

## Context

`SPEC.md` §7.2 specifies *what* the key hierarchy computes. It does not say what computes it. Four things need a concrete answer before `:core:crypto` can be written: AES-GCM wrapping, HKDF-SHA256, the PBKDF2 inside the BIP-39 seed derivation, and the BIP-39 wordlist itself. Argon2id is a fifth, deferred with KEK-C to P1.

Two constraints dominate:

1. **The 15 MB APK budget** (§11), already contested by SQLCipher's native libraries, a bundled variable font, and — at P4 — a bundled ML Kit model. Anything with a native `.so` gets multiplied per ABI.
2. **§7.2 now requires the phrase→key derivation to be byte-exact and locked by committed golden vectors**, because a change to it orphans every backup a user has ever made. Any component sitting on that path must be immune to platform variation across OEM ROMs.

## The principle applied throughout

**Never implement a cryptographic primitive. Do implement RFC-specified constructions over vetted primitives.**

Hand-rolling AES, SHA-256, or HMAC would be indefensible. Hand-rolling HKDF or PBKDF2 — which are short, fully specified, deterministic loops over `javax.crypto.Mac` with official test vectors — is routine, and it removes a dependency *and* a platform-variance risk from the one derivation that must never change. That line is where every decision below falls.

## Decisions

| Need | Choice | Dependency added |
|---|---|---|
| Database encryption | `net.zetetic:sqlcipher-android` (already locked, §7.1) | native, pre-existing |
| AES-256-GCM wrap/unwrap, backup cipher | Platform `javax.crypto` — `AES/GCM/NoPadding`; `AndroidKeyStore` provider for KEK-A | **none** |
| HKDF-SHA256 | Hand-rolled, RFC 5869, over `javax.crypto.Mac` | **none** |
| PBKDF2-HMAC-SHA512 (BIP-39 seed) | Hand-rolled, RFC 2898, over `javax.crypto.Mac` | **none** |
| BIP-39 wordlist + checksum | Official English wordlist bundled as a raw asset (~13 KB) | **none** |
| CSPRNG | Default `SecureRandom()` | **none** |
| UUIDv7 | Hand-rolled per §6.0 | **none** |
| Argon2id (KEK-C) | **Deferred to P1** — analysis recorded below | TBD |

**Net new dependencies: zero.** That is the headline result and it is what buys room in the APK budget for the bundled ML Kit model at P4.

### AES-GCM — platform, not Tink

Tink is well-audited and misuse-resistant, and on a larger project it would be the default answer. It loses here on fit rather than on quality:

- It is an estimated ~0.8–1.5 MB after shrinking (needs measurement if ever reconsidered) against a budget already under pressure.
- Its value is concentrated in *key management* — keysets, rotation, envelope encryption. §7.2 already specifies our key management in detail, and §5.9 specifies our own on-disk container. We would be adopting Tink's keyset format and then not using it.
- Our actual GCM surface is tiny: wrap 32 bytes under KEK-A, wrap 32 bytes under KEK-B, encrypt one backup blob. Three call sites.

The one real risk of using GCM directly is **nonce reuse**, which is catastrophic for GCM. Our usage makes it structurally hard: every wrap and every backup generates a fresh 12-byte nonce from `SecureRandom`, and no operation ever re-encrypts under an existing key+nonce pair — a re-wrap writes a whole new blob with a new nonce. This is asserted by `NonceUniquenessTest`, which generates many wraps and fails on any collision.

### HKDF and PBKDF2 — hand-rolled, and why that is the safer choice here

There is no HKDF in the Android platform at `minSdk 26`. The alternatives are Tink (rejected above) or BouncyCastle (`HKDFBytesGenerator`), which drags in `bcprov` for one class. HKDF is an extract step (one HMAC) and an expand step (a short HMAC loop) — roughly 30 lines, with RFC 5869 Appendix A test vectors committed alongside.

PBKDF2 is the more interesting call, because the platform *does* offer `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")` on API 26+. It is rejected anyway:

`PBEKeySpec` takes a `char[]`, and Android's PBKDF2 implementations have historically disagreed about how characters are encoded into bytes — the well-known 8-bit-truncation behaviour. For an ASCII BIP-39 mnemonic this would very likely work. But "very likely works" is not the standard for the one function whose output, if it ever differs by a single byte on some OEM ROM, makes that user's backups permanently undecryptable. §5.8 already establishes that this project does not trust OEM platform data where correctness is load-bearing (`java.util.Currency` exponents); the same reasoning applies with far more force here.

Hand-rolling takes UTF-8 bytes directly and eliminates the character-encoding question entirely. ~25 lines, validated against the official BIP-39 test vectors.

### BIP-39 wordlist — bundled asset, not a library

Libraries exist (`cash.z.ecc.android:kotlin-bip39` is small and reasonably maintained). But the wordlist is a static 2048-entry file, and §7.2's golden-vector requirement means we must own and test the entropy↔mnemonic↔checksum logic regardless. A dependency that we still have to wrap in our own verified layer is not pulling its weight.

The asset ships with two tests: its SHA-256 must match the canonical published wordlist, and the full official BIP-39 English vector set must round-trip. Checksum validation runs **before** any KDF work, per the §7 danger-zone rule — a typo must not cost the user a KDF round and look like a hang.

### Argon2id — deferred with KEK-C, analysis recorded

Recorded now so P1 does not repeat it:

| Option | Native `.so` | Notes |
|---|---|---|
| `com.lambdapioneer.argon2kt` | yes | Actively maintained, purpose-built for Android. Estimated a few hundred KB per ABI — must be measured, and it multiplies across ABIs. |
| BouncyCastle `Argon2BytesGenerator` | no | Pure Java. Used directly rather than registered as a JCE provider, R8 should shrink it hard, but the residual size needs measuring. Expect it to be materially slower at m=64 MiB — possibly seconds — which needs benchmarking before it is called acceptable for an interactive unlock. |

**Note for P1:** if the answer turns out to be "ship a native library, on every install, for a convenience feature that only helps when the Keystore has been invalidated *and* the user would rather not type 24 words" — that is itself a strong argument for dropping KEK-C rather than implementing it. Decide that before writing the code, not after.

## Consequences

**What this makes easy.** No new dependencies, no native libraries, no APK cost, nothing to track for CVEs beyond SQLCipher and the platform. The derivation path is fully ours and fully pinned, which is exactly what §7.2 demands.

**What this makes hard.** We own ~55 lines of KDF code. Reviewers must treat it as security-critical, and the golden vectors are not optional decoration — they are the mechanism that makes owning this code safe. `CLAUDE.md` §7 already states the rule: if a vector test fails, the code is wrong, never re-record the fixture.

**What we now have to maintain forever.** The HKDF and PBKDF2 implementations, the bundled wordlist and its integrity test, and the committed golden vectors for seed, KEK-B, backup key, and keyCheck.

**What would make us revisit this.** A platform HKDF landing at our `minSdk`; a demonstrated flaw in our KDF implementations; or Tink becoming necessary for a reason we do not currently have. Reconsidering Argon2id at P1 is already scheduled and is not a reopening of this ADR.

## Verification

- `Rfc5869HkdfVectorTest` — RFC 5869 Appendix A vectors.
- `Bip39VectorTest` — official English vectors, entropy ↔ mnemonic ↔ seed.
- `Bip39WordlistIntegrityTest` — SHA-256 of the bundled asset.
- `KeyDerivationGoldenVectorTest` — the §7.2 fixed mnemonic → seed, KEK-B, backupKey, keyCheck. **Never re-recorded.**
- `NonceUniquenessTest` — no GCM nonce collision across many wraps.
- APK size budget enforced by the `assemble` CI job (§15.4).
