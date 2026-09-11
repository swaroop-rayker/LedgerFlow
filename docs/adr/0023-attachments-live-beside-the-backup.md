# ADR-0023 — Attachments are DEK-sealed in `filesDir`, and phrase-sealed *beside* the `.lfbk`, not inside it

- **Status:** Accepted
- **Date:** 2026-09-08
- **Deciders:** Swaroop (owner; decision delegated to the engineer), lead engineer
- **Supersedes / Superseded by:** none. **Closes `SPEC.md` §16 Q5** and adds the
  `attachment` table to §6.1's shipped schema.
- **Spec sections touched:** `SPEC.md` §5.3, §5.9, §6.1, §7.1, §7.2, §16 Q5;
  `CLAUDE.md` §2 Law 5, §7 (backup writer)

## Context

P4 stores receipt images — the largest thing this app will ever hold. Schema v10
has no `attachment` table; §6.1 specifies one and nothing has written it.

Two of the three questions are already answered by the spec and are not reopened
here. §7.1: attachments are "AES-256-GCM per-file, same DEK, stored in
`filesDir/attachments/`" — internal storage only, never `cacheDir` (Law 5).
That stands.

What is genuinely open is the part §16 Q5 asks — retention — and a third
question the spec never faced: **does a `.lfbk` carry image bytes?** §5.9
specifies the container as a single GCM blob over a JSON payload with a
`plaintextLen u64`, and §13.1's P0 criterion promises a phrase-only restore
returns "every row". Images are not rows, and nobody decided what happens to
them.

That question has to be answered before the first image is written, because it
determines what the app can honestly tell a user at the moment they attach one.

## Options considered

### Option A — Metadata only; images are not backed up

| | |
|---|---|
| Summary | `attachment` rows travel in the `.lfbk`; the files do not |
| Cost | A factory-reset restore returns every entry and no images, permanently |
| Risk | Low technically. Contradicts §13.1's "every row comes back" in spirit, and the loss is silent unless designed against. |

### Option B — Image bytes inside the `.lfbk`

| | |
|---|---|
| Summary | `formatVersion` → 2, a blob section after the ciphertext, manifest in the authenticated header |
| Cost | Touches the GCM/AAD container — the most dangerous code in the app (`CLAUDE.md` §7). Requires the backup→wipe→restore round-trip green before commit. |
| Risk | **The nightly backup is the disqualifier.** §5.9 runs a `PeriodicWorkRequest` nightly and §7 requires write → fsync → *decrypt-and-parse to verify* → rename. That makes every night a full rewrite **and full decrypt-verify** of the entire image set, growing without bound, for data that did not change. Base64 in JSON also inflates ~33% and forces the whole payload through memory at once. |

### Option C — Images beside the `.lfbk`, in the same backup folder

| | |
|---|---|
| Summary | Each attachment written once into the user's existing SAF backup tree, individually sealed under a phrase-derived key |
| Cost | The unit the user must keep is a folder, not a file |
| Risk | A user who copies only the `.lfbk` loses images — which is Option A's failure, but now detectable and reportable. |

## Decision

**Locally: `filesDir/attachments/`, AES-256-GCM under the DEK, as §7.1 already
specifies. In the backup: each attachment written once into the user's existing
SAF backup tree as an individually sealed blob under a phrase-derived key —
`HKDF(seed, salt = per-file, info = "lfbk-attachment-v1")`. The `.lfbk` container
stays `formatVersion` 1 and is not touched.**

**The hinge is that §5.9 already grants a SAF *tree* URI, not a file path.** The
unit the user has already chosen is a folder, so putting images in it introduces
no new concept and no new grant. That is what makes Option C cost what Option A
costs while delivering what Option B delivers.

This mirrors the database exactly: DEK at rest on the device, phrase-derived the
moment it can leave — which is what §7.2's threat-model table already prescribes,
and it is why the backup copy is *not* simply the DEK-sealed file copied across.
A leaked backup folder must be as useless as a leaked `.lfbk`.

**It introduces no new key material class and no third wrap.** ADR-0011 forbids
adding a wrap on the DEK path; this adds none. It derives a *new purpose* from
the existing seed with a distinct `info` string, exactly as §7.2 already does for
`backupKey` and `keyCheck`. §5.9 says "`info` strings are versioned; changing one
is a breaking format change" — adding one is not changing one.

**Writes are incremental.** An attachment is written to the tree once and never
rewritten; a night with no new receipts writes nothing. This is the property
Option B could not have, and it is most of the argument.

### The UX half, which is not separable

**It degrades honestly.** If the user moves the `.lfbk` alone, restore succeeds
and reports *"12 receipt images weren't found alongside this backup"* — a true,
specific sentence naming a number, not a silent gap. The attach screen states
up front that receipts live with the backup folder, so it is a stated property
rather than a discovery made after a factory reset.

This is the same rule the purge dialog and the pre-migration snapshot already
follow (ADR-0019): the app says what it can and cannot do for you, and never
implies a durability it does not have.

### Retention — Q5 answered

**Store only the ≤1600px image the recogniser actually read; keep it forever; no
timed purge.**

§5.3 already downscales to ≤1600px long edge for recognition. Storing that
rather than the camera's original is a ~15× reduction (~4 MB → ~250 KB) *and* it
is the honest record: it is the image the pipeline saw, so a later "why did OCR
read this wrong" question is answerable. Store the downscaled **colour** frame,
not the deskewed/thresholded one, which is unreadable to a human.

**No auto-purge.** D-09's 90-day rule exists because a raw message body is the
most sensitive text the app holds *and it rides inside a `.lfbk` that can leave
the device*. Neither clause transfers: an image is not more sensitive than the
entry describing it, and under this decision it is not inside the `.lfbk`.
Deleting a user's receipts on a timer would also contradict every other
retention choice here — soft delete everywhere, a bin, a type-DELETE gate.

Instead the growth is made **visible**: Settings shows "Receipts — N images,
M MB" with a manual bulk delete behind the `Warning` treatment the bin's erase
uses. The user's call, with the number in front of them.

### Amendment (P4, implementation): the local seal uses a key *derived from* the DEK

This ADR says attachment files are sealed "under the DEK", restating §7.1.
Building it surfaced something nobody had checked: **nothing retains the DEK
after unlock.** `VaultSession` hands it to `LedgerFlowDatabaseFactory` and
calls `dek.destroy()` on the next line, and SQLCipher's own copy lives behind
`PRAGMA cipher_memory_security = ON`, which keeps it out of swappable memory.

Honouring the wording literally therefore meant one of three things, and none
of them is what the wording implies:

| | |
|---|---|
| **Retain the `Dek`** | A *second* copy of the database key in the JVM heap for the whole process lifetime, with none of `cipher_memory_security`'s protection. Least code, worst blast radius. |
| **Re-unwrap per write** | A Keystore round-trip per attachment. No long-lived copy, but a new failure mode when the Keystore wrap is absent. |
| **Derive a distinct purpose** | `HKDF(dek, info = "ledgerflow-attachment-local-v1")`, retained instead of the DEK. |

**Decided: derive.** `AttachmentKey.local`, held by `VaultSession` for exactly
the database handle's lifetime and zeroed on `close()`.

The reasoning is this ADR's own, applied to the local copy instead of the
backup one. Above, for the phrase-derived backup seal, it argues that deriving
"introduces no new key material class and no third wrap… it derives a *new
purpose* from the existing seed with a distinct `info` string", and that §5.9's
"`info` strings are versioned; changing one is a breaking format change" means
**adding one is not changing one**. All of that holds here. ADR-0011's ban is
on adding a wrap to the DEK *path*; this adds none, and there is still exactly
one DEK wrapped by exactly two factors.

What it buys over the literal reading is worth the paragraph: **a heap
compromise yields the user's receipt images and not their ledger.** The
literal reading would have handed over both.

Three implementation notes, all load-bearing:

- **The derivation is deterministic and unsalted.** An image sealed today has
  to open tomorrow, so the key must be a pure function of the DEK; a random
  salt would be a bug wearing the costume of an improvement, and a per-install
  salt is one more thing to store, back up and lose. RFC 5869 §3.1 says an
  extract salt is unnecessary when the IKM is already uniformly random, which
  a DEK is. Per-*file* uniqueness is `AesGcm`'s nonce, which it generates
  itself and offers no way to supply.
- **It lives in its own file, not in `KeyDerivation`.** That file is pinned
  byte-for-byte by `KeyDerivationGoldenVectorTest` because every `.lfbk` ever
  written depends on it. This key protects files on one device, and its golden
  vector is correspondingly *re-recordable in principle* — changing it orphans
  local images that the phrase-sealed backup copy can still restore, where
  changing `KeyDerivation` orphans every backup that has ever existed. Two
  different change rules should not share a file.
- **The committed vector was verified against an independent RFC 5869
  implementation**, not recorded from this code's own output. A vector copied
  out of the thing it tests proves only that the code agrees with itself.

**Unchanged:** the backup copy. It is still phrase-derived, still sealed with
`info = "lfbk-attachment-v1"`, still written *beside* the `.lfbk` rather than
inside it. The two seals are deliberately named apart so that nobody later
assumes one file opens with the other's key.

## Consequences

**What this makes easy.** A phrase-only restore that returns images as well as
rows, without touching the `.lfbk` container or its round-trip guarantees. A
nightly backup whose cost is proportional to new receipts rather than to total
receipts.

**What this makes hard.** The backup is now two things in one folder, and a user
who treats the `.lfbk` as *the* backup will lose images. The mitigation is
wording, not machinery, which means it can rot. Restore also gains a partial
state — rows present, files absent — that every surface drawing an attachment
must handle.

**Two implementation notes that are load-bearing, recorded because both are the
kind of thing found later as a bug:**

- **`attachment.file_path` is relative to `filesDir/attachments/`, never
  absolute.** `filesDir` differs across reinstall and restore; an absolute path
  is a BUG1/BUG2 with a long fuse. §6.1 says only `TEXT NOT NULL`.
- **`sha256` is over the plaintext, computed before encryption.** It dedupes the
  same receipt attached twice and gives integrity a meaning; a hash of ciphertext
  under a fresh nonce dedupes nothing.

**What we now have to maintain forever.** The `"lfbk-attachment-v1"` `info`
string, which is now part of the backup format in the same sense the others are.
The `attachment` table's `BackupPayload` list and CSV file (metadata only) —
mandatory the moment the table exists, per `ExportCoversEveryTableTest`.

**What would make us revisit this.** A user report that the two-part backup was
misunderstood in practice — that is the risk this decision takes, and it is a
UX signal, not a technical one. Or a decision to support a single-file export
for transfer, which would reopen Option B with a different justification.

## Verification

- **`AttachmentBackupRoundTripTest`** — seed attachments, back up, destroy both
  the database and the local files, restore from the phrase alone, assert
  byte-equality of every recovered image *and* row-level equality of the
  metadata. The existing `BackupRestoreRoundTripTest`'s standard: content
  equality, not counts.
- **`AttachmentMissingFileTest`** — restore from a `.lfbk` with no sibling
  images and assert the surfaces report the count rather than crashing or
  showing a blank. The honest-degradation promise, tested.
- **`AttachmentPathIsRelativeTest`** — no stored `file_path` is absolute.
  Shipped as `AttachmentStoreInstrumentedTest.theStoredPath_isRelative`, with
  the rest of the store's properties beside it: the bytes on disk are not the
  plaintext, the file is in `filesDir` and not `cacheDir`, the same image
  twice stores once, a missing file reads as null rather than crashing, a
  tampered file does not authenticate, and no `.tmp` survives. Proven
  non-vacuous by mutation — writing plaintext instead of sealing turns exactly
  two of them red.
- **`AttachmentKeyTest`** — the derivation above: deterministic in the DEK,
  distinct from the DEK itself and from every phrase-derived purpose, and
  matching a vector computed outside this codebase.
- **`bannedApiCheck`** already enforces Law 5's `cacheDir` ban, which covers the
  "decoded-image scratch only" rule this feature is most likely to strain.
