# ADR-0011 — KEK-C (optional passphrase wrap) is dropped, not deferred again

- **Status:** Accepted
- **Date:** 2026-08-17
- **Deciders:** Swaroop (owner, delegated), lead engineer
- **Supersedes / Superseded by:** none. Resolves the P1 deferral recorded in `SPEC.md` §7.2 and the open item in ADR-0010 §"Argon2id — deferred with KEK-C".
- **Spec sections touched:** `SPEC.md` §7.2, §7.3, §7.4, §11

## Context

`SPEC.md` §7.2 specifies a DEK wrapped independently by three factors:

| | Factor | Status entering P1 |
|---|---|---|
| KEK-A | Android Keystore | shipped in P0 |
| KEK-B | 24-word BIP-39 phrase, mandatory | shipped in P0 |
| KEK-C | user passphrase, Argon2id, optional | **deferred to P1 — this ADR** |

P0 shipped KEK-A and KEK-B and reserved the extension point: the `wrapped_dek_pass.bin` filename slot and the `app_meta.dekWrapVersion` counter both account for a third wrap that does not yet exist. ADR-0010 deferred the Argon2id library choice alongside it and left an explicit note: *if the answer turns out to be "ship a native library, on every install, for a convenience feature", that is itself a strong argument for dropping KEK-C rather than implementing it. Decide that before writing the code, not after.*

This is that decision, and it is forced now rather than later because KEK-C is not an isolated component. It is a branch in the §7.3 unlock state machine, a step in the §7.4 onboarding flow, and a third re-wrap case in both §7.7 rotation procedures. Writing those flows around a factor we may not ship, or retrofitting the factor into flows already written, are both worse than deciding first.

**What KEK-C actually buys.** Precisely one scenario: the Keystore has been invalidated (factory reset, device migration, some OEM Keystore corruption) **and** the user would prefer typing a passphrase to typing 24 words. It is not a recovery factor in any case KEK-B does not already cover — §7.2 is explicit that KEK-C never protects a `.lfbk`, so it cannot participate in cross-device restore at all. Its entire value is keystroke count on one screen the user hopes never to see.

**What it costs is not one number.** Binary size is the obvious cost and the least interesting one.

## Options considered

### Option A — `com.lambdapioneer.argon2kt` (native Argon2id)

| | |
|---|---|
| Summary | Purpose-built Android binding over the reference Argon2 implementation. Actively maintained. |
| Cost | A native `.so` **per ABI**, against a 15 MB budget (§11) already absorbing SQLCipher's native libraries, a bundled variable font (§9.2 forbids runtime download), and — at P4 — the bundled ML Kit model that §11 establishes as the only option consistent with Law 6. |
| Risk | The one component in `:core:crypto` with a native dependency, added for the least load-bearing factor in the hierarchy. |

It is the correct library if we are shipping Argon2id at all. That is the question, not this.

### Option B — BouncyCastle `Argon2BytesGenerator` (pure Java)

| | |
|---|---|
| Summary | No native code. Used directly as a class rather than registered as a JCE provider, so R8 can shrink around it. |
| Cost | Residual size after shrinking is unmeasured. More importantly, pure-Java Argon2id at `m=64 MiB, t=3, p=4` is expected to be materially slower than the native implementation — plausibly seconds on a mid-tier device. |
| Risk | **The mitigation is worse than the problem.** The obvious fix for a slow unlock is to lower the memory parameter. But `m` *is* the security of the wrap. Tuning Argon2id down until it feels interactive turns KEK-C into a weak wrap on the DEK — and §7.2's central argument is that the effective security of a multi-wrapped key equals its **weakest** wrap. |

Option B does not fail on performance. It fails because the pressure it creates points directly at the parameter that must not move.

### Option C — Drop KEK-C; keep the extension point reserved

| | |
|---|---|
| Summary | Ship KEK-A + KEK-B permanently. `wrapped_dek_pass.bin` and `dekWrapVersion` stay reserved. No Argon2id, no third unlock branch. |
| Cost | A user whose Keystore is invalidated types 24 words. That is the whole cost. |
| Risk | If the "type 24 words" screen proves genuinely painful in practice, we have to reopen — with the extension point still sitting there, unused. |

### Option D — A non-Argon2id passphrase KDF (PBKDF2 at high iteration count)

Rejected in one line, because the reader will ask: we already own a verified PBKDF2-HMAC-SHA512 (ADR-0010), so this is nearly free to build. It is rejected because PBKDF2 is memory-cheap by construction and therefore GPU/ASIC-friendly, which is the entire reason Argon2id exists. Wrapping the DEK — the single value that decrypts everything — behind a human-chosen passphrase and a memory-hard-free KDF is a worse security posture than not offering the convenience at all.

## Decision

**KEK-C is dropped from LedgerFlow v1. The key hierarchy is KEK-A (Keystore) + KEK-B (24-word phrase), permanently, and no Argon2id dependency is taken.**

The argument that decides it is not binary size — it is the **state machine**. §7.3 already has to be correct across Keystore success, Keystore invalidation, canary mismatch, phrase entry, re-wrap, and `.lfbk` restore. §7.7 adds two rotation procedures, each of which must re-wrap every live factor. KEK-C adds a conditional branch to every one of those paths, and each branch is a place where a wrap can be written stale, missed during rotation, or left pointing at a superseded DEK. Every one of those bugs is shaped like data loss, which §1.2 P4 classifies as a P0 incident rather than a bug.

We would be accepting that risk surface — permanently, on every install, in the most safety-critical module in the codebase — to save keystrokes on a screen whose entire premise is that something has already gone wrong. That trade is not close. It is the clearest case in this project of a feature whose cost is concentrated exactly where the cost hurts most.

**The decision is cheap to reverse and expensive to un-ship.** The extension point stays reserved: `WrappedDekStore` keeps its `KekId.PASSPHRASE` slot and `dekWrapVersion` keeps accounting for a third wrap, so adding KEK-C later is additive and requires no format change. Shipping it now and removing it later would mean migrating users who had enabled it, which is a data-loss-shaped operation for a convenience feature. When a decision is asymmetric like that, take the reversible side.

This does not reopen ADR-0003 or §7.2's design. Multi-wrap, phrase-primary, backups phrase-only — all unchanged. What changes is that the third slot ships empty.

## Consequences

**What this makes easy.** `:core:crypto` gains no native dependency and no new library of any kind, holding ADR-0010's zero-new-dependencies result through P1. The §7.3 unlock flow is a two-factor state machine, which is the version that can be exhaustively tested. Both rotation procedures in ADR-0009 re-wrap exactly two factors. The 15 MB budget keeps its headroom for the P4 ML Kit measurement, which is the one place §11 admits it may not fit.

**What this makes hard.** A user whose Android Keystore is invalidated must type 24 words to get back in. This is the accepted cost and it must not be papered over: the Recovery screen therefore has to be *good* — BIP-39 autocomplete, per-word validation, checksum validated before any KDF work (§7 danger zone), clear progress, and no dead ends. The friction we declined to remove with a passphrase has to be removed with interaction design instead. That is now a P1 requirement, not a nicety.

**What we now have to maintain forever.** Nothing new. That is the point. The reserved-but-unused `KekId.PASSPHRASE` slot and its `dekWrapVersion` accounting stay as they are, with a comment pointing at this ADR so a future reader does not mistake the empty slot for an oversight.

**What would make us revisit this.** Concrete triggers, either of which is sufficient: (a) real usage or dogfooding shows the 24-word Recovery screen being hit often enough that its friction is a genuine product problem — not hypothesised, observed; or (b) a memory-hard KDF lands in the Android platform at our `minSdk`, removing the dependency question entirely. Absent one of those, this stays closed.

## Verification

- `DekManager` exposes exactly two unwrap paths (`unlockWithKeystore`, `unlockWithPhrase`). A unit test asserts the public unwrap surface has not grown, so KEK-C cannot reappear unremarked.
- `WrappedDekStoreTest` asserts `KekId.PASSPHRASE` remains a reserved, never-written slot.
- No Argon2id dependency appears in `gradle/libs.versions.toml`; the absence is load-bearing and CI's APK-size job (§15.4) is the backstop.
- The Recovery screen's word entry is covered by the same `WordChallenge` validation tests as onboarding, since the friction argument above makes that screen's quality part of this decision rather than incidental to it.
