# ADR-0029 — The SQLCipher key is derived once per launch, not once per connection

- **Status:** **Accepted** (owner, 2026-10-16: "yes fix it", on the measured
  split below).
- **Date:** 2026-10-16
- **Deciders:** Swaroop (owner), lead engineer
- **Spec sections touched:** `SPEC.md` §7.1 (Database), §8 (BUG56), §9.3
  (Home's measurement), §11; `docs/PERF-120HZ-PLAN.md` §6; `CLAUDE.md` §7.

## Context

Home reports fully drawn 2.61–2.69 s after a cold launch of LF Bench
(SM-S721B, Android 17, thermal status 0), against a first frame of ~140 ms.
Temporary markers on six cold launches (2026-10-16) put the time here:

| Step | ms |
|---|---|
| Keystore unwrap of the DEK | 268 |
| `PreMigrationGuard.assess` opens the file to read `user_version` | 445 |
| Room's writer connection | 405 |
| Three reader connections, one after another | ~430 each |
| Everything Home computes (snapshot plus three totals) | 36 |

`LedgerFlowDatabaseFactory` handed SQLCipher the DEK as a **passphrase**. So
every connection ran SQLCipher's key derivation, PBKDF2-HMAC-SHA512 at
256,000 iterations (~425 ms on this phone). That covered the guard's open, the
writer and each reader the pool opened. The sqlcipher pool opens a connection
while holding its lock, so the derivations ran one after another: five of
them, about 2.1 s of a 2.65 s launch. The pool allows up to ten connections.

The derivation's input is the DEK, which is already 256 bits from a CSPRNG.
PBKDF2 makes a low-entropy passphrase expensive to guess. It adds nothing to a
key that cannot be guessed. But the derivation also *defines* the key the file
is encrypted under, so it cannot simply be dropped without re-encrypting every
vault.

## Decision

**Compute SQLCipher's derivation ourselves, once per launch, and key every
connection with its result in SQLCipher's raw-key form.**

- `DatabaseKey.forFile(file, dek)` (`:core:database`) reads the file's 16-byte
  salt (its first 16 bytes; SQLCipher 4 keeps the header encrypted except the
  salt). It then computes `PBKDF2-HMAC-SHA512(dek, salt, 256000, 32)` with
  `:core:crypto`'s `Pbkdf2`. This is byte for byte what SQLCipher computes from
  the passphrase. The result goes to SQLCipher as
  `x'<64 hex key><32 hex salt>'`, which SQLCipher accepts as the derived key
  itself and does not derive again.
- `VaultSession` derives once, then hands the same key to `PreMigrationGuard`
  (assess, snapshot, verify) and to Room's factory. Each of those now costs
  milliseconds to open.
- **When there is no file yet** (onboarding, restore into a fresh vault), there
  is no salt to read. The DEK is passed as the passphrase exactly as before.
  SQLCipher generates the salt and derives once, at creation only.

## Measured (2026-10-16, after the decision)

- **One derivation in Kotlin vs SQLCipher's native one:** `Pbkdf2` at
  256,000 iterations took 645 ms in the debug test APK. A native passphrase
  open of the same file took 499 ms, and the raw-key open took 1 ms. So the
  Kotlin derivation is 1.3× SQLCipher's, and it replaces five of them.
- **Fully drawn on LF Bench**, base (fd5e635) and new builds alternated, two
  rounds of a warm-up plus 3 cold launches each, thermal status 0:
  **2.650–2.748 s → 0.707–0.734 s**. First frame was unchanged (132–220 ms).

## What this does not change

- **The file, the encryption key, the salt and `kdf_iter` are all unchanged.**
  The raw key *is* the key SQLCipher derived before, from the same DEK and the
  same salt at the same 256,000 iterations. There is no rekey, no rewrite, no
  migration and no format change. An existing vault opens as before, and a
  build before this ADR can still open a vault this build has used.
- **SPEC §7.1's "do NOT lower `kdf_iter`" holds.** The derivation still runs at
  256,000 iterations. It now runs once per launch instead of once per
  connection.
- **No new wrap, no new key material, no new factor** (ADR-0011). The DEK is
  still unwrapped by Keystore (KEK-A) or the phrase (KEK-B). The derived key
  exists only in memory, as the DEK does.
- **Backups are untouched.** A `.lfbk` is phrase-derived (§5.9) and never saw
  this key.

## Exposure

The derived key decrypts the database, so it is exactly as sensitive as the
DEK. It is held where the DEK was held before this change, inside sqlcipher's
open-helper configuration for the life of the pool, because new connections
need it. It is zeroed at the same points the DEK is, it is never a `String`
(the factory takes bytes so they can be zeroed), and its `toString` is
redacted.

## Rules this imposes

1. **Nothing outside `DatabaseKey` builds a raw key.** It is the one place that
   knows the salt's position, the iteration count and the hex form.
2. **The equivalence is tested on the device, both ways** (BUG56,
   `Bug56_DerivedRawKeyOpensPassphraseVaultTest`). A vault created under the
   passphrase opens under the derived raw key. A vault used under the raw key
   opens under the passphrase. A wrong DEK opens neither. If a SQLCipher upgrade
   changes its default KDF, iterations or salt handling, this test fails. That
   is a format migration for every existing vault, written under the old
   default, and needs its own ADR. It is never a constant to bump in
   `DatabaseKey` and never a test to re-record.
3. **`kdf_iter` stays the library default and is not restated as a PRAGMA.** If
   it were set on connections, `DatabaseKey`'s constant and the PRAGMA could
   drift apart. Rule 2 checks the default directly.

## Alternatives rejected

- **The DEK as the raw key, with no derivation at all** (~2.1 s saved, not
  ~1.7 s). The key would change, so every existing vault would need a one-time
  full re-encryption (`PRAGMA rekey`). That is a rewrite of the user's only copy
  of their ledger, and it is also SPEC §7.1's "do NOT lower kdf_iter" in
  spirit. The extra ~0.4 s is not worth that risk.
- **Cap the pool at one reader** (~0.86 s saved). It keeps all five costs
  except two, and serialises every read behind one connection.
- **Skip the guard's open.** That would mean reading `user_version` from
  somewhere other than the file, which weakens the one check that sees a
  downgrade (BUG3) before Room runs a migration.
