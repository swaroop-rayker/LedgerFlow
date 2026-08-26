# ADR-0019 — The pre-migration snapshot is a database file copy, not a `.lfbk`

- **Status:** Accepted
- **Date:** 2026-08-26
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. Amends `SPEC.md` §8.1.
- **Spec sections touched:** `SPEC.md` §8 (BUG8(d)), §8.1, §7.3; `CLAUDE.md` §7 (`:core:database` migrations)

## Context

`SPEC.md` §8 lists a pre-migration backup as BUG8's fourth countermeasure:
"before any migration runs, a `.lfbk` snapshot is written and verified. If
migration throws, restore automatically and surface a report." §8.1 gives it an
operating design — an Upgrading screen, a `2.2 × dbSize` free-space check, an
automatic restore on failure. `CLAUDE.md` §7 states it as an existing property
of the codebase: "A pre-migration `.lfbk` backup is written and verified before
any migration runs. Don't remove this."

**It has never existed, and as specified it cannot.**

`DatabaseBackupManager.writeBackup(destination, seed)` takes the BIP-39 seed,
because ADR-0011 settled that a `.lfbk` is protected by the recovery phrase and
by nothing else — the file can leave the device, so a device-local factor must
never be what stands between an attacker and it. The consequence is that the
app **never holds the seed** outside the two moments the user supplies it:
onboarding, where the phrase is generated, and Recovery, where it is typed.
A normal launch unlocks through the Keystore and has a DEK, not a seed.

So `writeBackup` has no production caller. Its only callers are in
`BackupRestoreRoundTripTest`. The same gap is already acknowledged one section
away in `CLAUDE.md` §7, about the purge dialog: "Never offer to back up first as
though the app could. Backups are phrase-derived (ADR-0011) and the app never
holds the phrase." §8.1 asks for exactly the thing that rule forbids, and the
contradiction has been latent because no migration had run against real user
data since the unlock flow was wired.

Schema v6 (the P2 ingest tables) is the first one that would. That forced the
question rather than created it.

## Options considered

**A. Prompt for the recovery phrase before every migration.** Keeps §8.1
literally: a real, portable, verified `.lfbk`. Rejected. It makes every schema
upgrade a twenty-four-word typing session, which is a large tax on a routine
event; worse, it puts the phrase onto the ordinary path repeatedly, when the
entire two-factor design exists so that the phrase is needed *rarely* and the
Keystore carries the common case. It also fails closed for a user who cannot
find their written copy — meaning the app would refuse to start after an update
for the exact person whose data is most at risk.

**B. Drop the pre-migration backup.** Delete the claim from `CLAUDE.md` §7 and
§8.1, and rely on the migration test suite plus whatever the user has exported.
Rejected. The tests are good and the migration chain runs on every PR, but
BUG8's countermeasure list is not decoration: a migration that throws halfway on
a device nobody can reproduce is precisely the case tests do not cover, and
leaving it with no rollback is how a schema change becomes data loss.

**C. Snapshot the encrypted database file.** Copy `databases/ledgerflow.db`
byte-for-byte before the migration, verify the copy by opening it with the DEK,
and copy it back if the migration throws.

## Decision

**Option C.** The pre-migration snapshot is a byte copy of the SQLCipher
database file, taken after a WAL checkpoint, verified by opening the copy with
the DEK and reading its canary and `user_version`.

The reasoning that makes this not a weakening is that a *backup* and a
*rollback snapshot* are different objects with different jobs, and §8.1
conflated them:

| | `.lfbk` backup | Pre-migration snapshot |
|---|---|---|
| Job | survive the device being lost, factory reset, or replaced | survive the next sixty seconds on this device |
| Leaves the device | yes, by design (SAF tree, possibly cloud-synced) | never — `filesDir`, deleted within a launch or two |
| Therefore protected by | the phrase, and only the phrase (ADR-0011) | the DEK, exactly as the live database already is |
| Portable across installs | yes | no, and does not need to be |

The snapshot introduces **no new key material and no third wrap** — §7's ban on
a third wrap without an ADR is untouched, because there is no additional wrap
here at all. The bytes on disk are the same bytes SQLCipher already wrote, under
the same DEK, in the same `filesDir`. An attacker who can read the snapshot can
already read the database it was copied from.

## Consequences

**§8.1's "determinate progress (bytes written / verified)" largely stops being
meaningful, and the screen changes shape accordingly.** That requirement was
written against a full export-and-reparse of every row, which is genuinely slow
and was the reason §8.1 insisted the work not sit on the cold-start path. A file
copy of a database this size is effectively instantaneous. The Upgrading screen
stays — it is still the state the app is in, it is still not cancellable, and it
must still never present as frozen — but it is an honest brief state rather than
a byte counter that would flash past. If a future database grows large enough
for the copy to be perceptible, the progress reporting can be added then, to a
mechanism that can actually report it.

**§8.1's retention line changes.** "Folded into the normal 5-backup rotation" no
longer applies: the snapshot is not a `.lfbk` and the rotation only understands
`.lfbk` files. It is deleted on the first launch that opens cleanly with no
migration pending — one launch later than the migration itself, so a migration
that succeeded but produced something wrong is still recoverable at the point
the user first sees the result.

**The `2.2 × dbSize` check stays, and is now justified differently.** It was
sized for snapshot + verification scratch + margin. With a file copy the
verification needs no scratch, but the migration itself does: the chain rebuilds
tables with `CREATE new / INSERT SELECT / DROP old`, which transiently holds two
copies of the largest table plus journal. 1× snapshot + migration working space
+ margin lands in the same place, so the number does not move.

**This does not give the user a backup, and must never be presented as one.**
It is not portable, it dies with the device, and it is gone within two launches.
The user still needs real `.lfbk` backups and the app still cannot make them
unattended. Nothing in the Upgrading screen may imply otherwise — the same rule
the purge dialog follows.

**A `-wal` file has to be checkpointed before the copy, not copied alongside
it.** Copying `.db` while a populated `-wal` exists produces a snapshot missing
the most recent writes, which is the quiet failure mode this whole mechanism is
supposed to prevent. `PRAGMA wal_checkpoint(TRUNCATE)` runs first; the snapshot
is then a single self-contained file.

**Downgrade is detected here too, and is not a migration.** Reading
`user_version` before opening for normal use means the guard is also the first
place that can see a database newer than the build. It routes to a block rather
than attempting anything, which is BUG3(c)'s behaviour arriving at the point
that first has the information.

## Verification

- `PreMigrationGuardTest` (instrumented, `:core:database`): a v5 database with
  seeded rows is snapshotted, migrated to v6, and the snapshot is shown to open
  under the DEK and still report `user_version = 5`.
- A forced migration failure restores the snapshot and the original rows are
  readable at the original version — the automatic-restore path, which is the
  only one in the app.
- An unverifiable snapshot aborts before the migration and leaves the original
  database untouched, mirroring the backup writer's rule that an unverified
  file is not a backup.
- The free-space check refuses rather than proceeding, and names the figure.
- `TESTING.md` row I6 covers the on-device Upgrading screen; row J2 covers the
  low-storage refusal. Neither is reproducible on a runner.
