# ADR-0020 — Notification-listener health lives outside the vault, in DataStore

- **Status:** Accepted
- **Date:** 2026-09-01
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none.
- **Spec sections touched:** `SPEC.md` §5.2, §16 Q16; `CLAUDE.md` §2 Law 5, §3

## Context

`SPEC.md` §5.2 asks for "a Dashboard health banner if the service has been dead
> 6 h". A banner that can say *how long* the listener has been dead needs a
persisted last-known-alive timestamp, because the interval it reports routinely
spans a process death — that is the failure it exists to report.

The write happens in `NotificationIngestService.onListenerConnected()`. That
callback runs when the system binds the listener, which is **at boot, in a
process with no Activity and often before the user has unlocked anything**.
`CLAUDE.md` §7 already names this class of caller and its trap: `VaultSession.requireDatabase()`
throws there, the throw lands in a `runCatching`, and the operation reports
success while doing nothing — BUG12 and BUG13, twice.

So the question is not "where do we like to put state", it is: **what storage
can be written by a UI-less callback at boot and still be readable when the
Dashboard asks?** Everything persistent in this app currently lives in one
SQLCipher file, and Law 5 constrains only the *directory* (`filesDir`), not the
encryption.

There is a second force. §16 Q16 — a message arriving when the vault cannot
open — is deferred, not closed, and its stated requirement is "somewhere
outside the vault to record that it happened (a DataStore counter, say)". Both
needs want the same shelf. This ADR builds the shelf; Q16 stays deferred and
unbuilt.

## Options considered

### Option A — `app_meta`, inside the encrypted vault

| | |
|---|---|
| Summary | A few more key/value rows beside `baseCurrency` and the allowlist fingerprint. |
| Cost | No new dependency, no new module, no new file on disk. Every listener bind performs a Keystore unwrap plus a SQLCipher open to write one `Long`. |
| Risk | Records nothing in exactly the state the banner exists to describe. |

The disqualifying property is the last row. A vault that cannot open — onboarding
never completed, a Keystore wrap that is gone — is one of the situations in
which the user most needs to be told capture is not working, and it is precisely
the situation in which this storage is unavailable. The banner would be blind
where it matters and chatty where it does not.

The cost is real too, if secondary: an unwrap-and-open on every bind, at boot,
to stamp a timestamp, on a path `CLAUDE.md` §8 already says should not spend
1 ms carelessly.

### Option B — `SharedPreferences`

| | |
|---|---|
| Summary | The platform's own key/value store. Zero dependencies. |
| Cost | Synchronous API. `getLong` on the main thread is a disk read. |
| Risk | No transactional guarantee, no `Flow`, and a well-known ANR shape on first access. |

Rejected quickly rather than omitted: it solves the same problem as Option C
with a worse API, and the codebase's whole read surface is `Flow`-shaped.

### Option C — DataStore Preferences, in `filesDir`, unencrypted

| | |
|---|---|
| Summary | `androidx.datastore:datastore-preferences`, already pinned in `libs.versions.toml` and so far unused. Brings the empty, already-included `:core:datastore` module to life. |
| Cost | One dependency newly *used* (not newly added). One small unencrypted file on disk. |
| Risk | Operational state sits outside the encrypted vault. |

## Decision

**Notification-listener health is persisted by `:core:datastore` in a DataStore
Preferences file under `filesDir`, unencrypted, behind a `:core:domain` port.**

The argument that decided it is availability, not convenience. The health signal
has to survive and be writable in states where the vault is unavailable, so
storing it in the vault makes the signal unreadable exactly when it is most
informative. A banner that cannot report the worst case is not a health banner.

What makes it *safe* to decide this way is that the stored value carries no
protected content. Three `Long` timestamps about this app's own listener, and
one boolean saying whether a screen has been shown: no amounts, no merchants, no
message bodies, no package names — nothing §5.2's privacy rule governs and
nothing D-09's retention window exists to bound. The line this ADR draws is therefore narrow and worth
stating as a rule rather than a precedent:

> **`:core:datastore` holds operational metadata about the app's own machinery.
> It never holds financial data, message content, or anything derived from
> either.** A value that would be interesting to an attacker who stole the file
> belongs in the vault.

### The permitted key surface

Four keys, and `DatastoreKeySurfaceTest` fails the build when a fifth appears
without this list being amended:

| Key | Why it is not vault data |
|---|---|
| `listener_last_connected_at` | A timestamp about this app's own service. |
| `listener_last_disconnected_at` | The same. |
| `listener_grant_observed_at` | When the app first saw its own grant held. |
| `notification_setup_seen` | Whether the first-run explainer has been shown. |

The fourth arrived in the same commit as the first three and is worth its own
line, because it is the one that is *about the user* rather than about the
service — and it still belongs here rather than in `app_meta`, on a property
that decides it rather than on convenience. **A `.lfbk` restore must not carry
it.** `app_meta` travels in a backup (§16 Q13), so a restore onto a new phone
would mark the explainer as already seen on a device where notification access
has never been granted — suppressing the one screen that grants it, at exactly
the moment it is needed. This store does not travel, so a new device asks again.
That is the correct behaviour and it is not available from the vault.

The decision is not close on availability and *is* close on tidiness — one
storage mechanism is easier to reason about than two. If a future need for
out-of-vault state cannot satisfy the rule above, that is the trigger to reopen
this rather than to widen it.

## Consequences

**What this makes easy.** The listener's health is readable whatever state the
vault is in, and writable from a boot-time callback without an unwrap. §16 Q16
now has a home to land in when the owner chooses to action it, at the cost of a
counter rather than a mechanism.

**What this makes hard.** There are now two persistence stories in the app, and
the next person adding state has a choice to make where previously there was
none — which is why the rule above is written as a rule. Backup and export are
also no longer total: a `.lfbk` restores the vault and does not carry the
listener timestamps. That is correct (a timestamp from another device would be
a lie about this one) but it means `ExportCoversEveryTableTest`'s guarantee —
"the backup covers every table" — no longer means "the backup covers every
persisted byte". The distinction is now load-bearing.

**What we now have to maintain forever.** The `:core:datastore` module and its
Hilt module; `ListenerHealthStore` as a domain port; and the rule that this
store holds no financial data, which `DatastoreKeySurfaceTest` enforces.

**What would make us revisit this.** Any of: a proposal to put user-meaningful
data in this store (reopen rather than widen); the platform gaining a supported
way to query listener binding state directly, which would remove the need to
persist anything; or a decision to make `.lfbk` cover app-operational state, at
which point the two stores need one restore path instead of none.

## Verification

- **`DatastoreKeySurfaceTest`** (`:core:datastore`) scans this module's sources
  for `*PreferencesKey("...")` declarations of **any** type and asserts the set
  matches the four keys named above — in both directions, so a stale permitted
  entry is caught as well as a new key. A guard whose only reference is the
  thing it guards is a restatement, so the permitted set is written out by name
  in the test rather than read from a list the production code exposes: if it
  were, adding a key would update both halves in one keystroke and the check
  would never fire. **Proved by adding a `stringPreferencesKey("last_captured_merchant")`
  and watching both directional assertions go red** while the discoverability
  guard stayed green.
- **`ListenerHealthEvaluationTest`** (`:core:domain`) pins the > 6 h rule and
  the states around it, off-device, against a fixed clock. **Proved by flipping
  `>` to `>=`**, which failed the boundary test and nothing else.
- **`NotificationListenerHealthDataStoreTest`** (`:core:datastore`, instrumented)
  asserts the write reaches **the bytes on disk**, in `filesDir`.

  The obvious version of this test — write through one store instance, read
  through a fresh one — proves nothing, and it is worth recording why, because
  it is the test anyone would write first. `preferencesDataStore` is a delegate
  on `Context`: every instance built against the same context shares one
  underlying `DataStore`, and a read is served from that object's memory cache.
  A value never written to disk at all would pass. Durability is therefore
  checked against the file's contents, which also pins Law 5 — `filesDir`, never
  `cacheDir`.
