# Session log — S13's continuation and S14: backups that exist, a restore, and receipt dates

Two sessions, one log: **S13's continuation** (2026-09-17/18, `b7e8319..18184d7`),
whose log was never written, and **S14** (2026-09-19, `f66ce47..cf1a245`).
Everything is on `main` and **pushed**. Schema **v11, unchanged** — neither
session added a migration.

**Verified state at `cf1a245`**

| | |
|---|---|
| `preMergeCheck --rerun-tasks --no-build-cache` | green, **3187 tasks executed, none from cache** (8m 57s) |
| Guards | schema, versionCode, corpus-order all pass |
| Device | Samsung SM-S721B, Android 16; owner's debug install on this build |
| Private corpus | `../LedgerFlow-receipts` is a local git repo (`4ecf923`), both receipts **owner-verified** |
| CI | red only on `ReceiptCorpusTest.theCorpusIsReachableInCi` — needs the private repo + token (§4) |

---

## 1. S13's continuation (2026-09-17/18)

- **BUG24** (`b7e8319`) — keywords match at word boundaries, and a tender row
  must be only a payment line. Misfiled items 87 → 8 of 300 measured lines.
- **Step 27** (`cc83ea3`) — approvals record `item_category_memory`. Nothing
  reads it yet.
- **The build sized for a 16 GB machine** (`dcf4d0a`, `84a7241`, `084d906`) —
  3 workers, 3g heap, Kotlin in-process, 15-minute idle timeout; measured Java
  peak 5.9 GB on an uncached gate. See the memory note on the RAM/heat budget.
- **ADR-0023's image copy beside the `.lfbk`** (`4d3e931`) — receipt images
  phrase-sealed into the backup folder.
- **Item 10** (`1fa4886`) — `Bip39ValidationTest` times the fastest run. The
  flake was never reproduced, so the fix is by construction, not measured.
- **Item 4** (`dfe55ff`) — the receipt-delete dialog stopped promising that an
  export keeps the photos.
- **Item 5 step 1** (`893d8d0`, `d2ef30a`) — fixtures carry the bill's date.
- **"Back up now"** (`5a20247..18184d7`, ADR-0025 part 1) — BUG25 (the keyboard
  could learn the phrase) first; a shared `PhraseEntry`; `DekManager.verifyPhrase`
  (read-only); `BackupFolder` over SAF with a verified write; the screen and a
  Settings row. Nightly backups struck: a scheduled job cannot seal a
  phrase-derived `.lfbk`. ADR-0025 part 2 (public-key sealing) proposed.

## 2. S14 (2026-09-19)

### 2.1 Restore (§16 Q11 closed, ADR-0026) — `f66ce47`

Every choice was put to the owner, with options, before code:
**(a)** a quiet link on onboarding's first screen; **(b)** the restored vault is
protected by **the backup's own phrase** — a fresh DEK wrapped under it, one
phrase for the user; **(c)** refuse when a vault exists (never replaces data);
**(d)** pick the backup **folder** (newest preselected) or a single file;
**(e)** a crash marker **file**, fsynced **before the wrap** — chosen over an
`app_meta` row once the wrap-before-database window was found; **(f)** keep the
backup's `app_meta` with `schemaVersion` and `backupTreeUri` corrected inside
the import's transaction.

The backup is decrypted and parsed **before anything is written**. An
interrupted restore routes back to itself and keeps background capture out;
rows already committed are recognised and not imported twice. **BUG26** found
on the way: an authentic but unparseable payload threw out of restore.

Tests: `RestoreFromBackupTest` (9, device, a second key directory and Keystore
alias as the "new phone"), plus ViewModel, messages, use case and name tests.
Mutation sweep: 14 mutations, 12 red; **two green and stated** in ADR-0026 —
resetting `schemaVersion` (every test backup is this schema's) and the
pre-import canary write (every backup carries the canary).

### 2.2 Real-folder testing on the owner's phone (item 2)

| Row | Result |
|---|---|
| D6 local | pass — verified `.lfbk`, `attachments/`, no `.tmp`, More shows the date |
| D6 **Google Drive** | **pass** — write, read-back, verify and promote against a real cloud provider |
| D6b wrong phrase | pass (see §3.1 — by accident) |
| D7 lost folder | **fail → BUG27 → fixed → pass** against the real SAF provider |
| D9 keyboard | pass — the phrase was not learned |
| D6c, D10 | not run (optional; D10 needs a guest user the owner creates) |

**BUG27** (`7867839`): renaming the backup folder left its grant held, so
"Back up now" went straight to the words and would have failed with a message
about disk space; and there was no way to change folders at all. Now a folder
counts only if it still answers (`BackupFolder.displayName()`), the screen
names it with **Change folder**, and a replaced grant is released — only on a
change, never on a failed check, which can be transient (Drive offline).

### 2.3 Decisions recorded (`618bd3f`)

- **Q22 decided:** the debit is the invoice value; a merchant-wallet top-up is a
  transfer and is discarded in the Inbox. No "transfer" concept exists yet.
- **ADR-0025 part 2** stays proposed; build later.
- **Backup reminder:** no backup, or older than 7 days.
- **Receipt dates:** the four rules (§2.4).
- Housekeeping: `s13-ocr-candidate` deleted (merged, never pushed); the private
  corpus became a local git repo.

### 2.4 Receipt dates (item 5) — `cc1c487`, `b9f2e71`, `3d9946f`

The owner verified **all 21 transcribed claims** in both real receipts against
the PDFs, on a local side-by-side page (never published). The manifest
changed in its own commit, before the code (`guard-corpus-order.sh`).

**Measured first**, with a throwaway probe through `PdfTextLayer` and
`ReceiptGeometry` on the device: digital PDFs never reach ML Kit; Zepto's row is
`Order No.: RGLOJVYNN22994A Date 20-07-2026` (the colon is lost in the text-layer
rebuild); bigbasket's `Invoice Date 2026-09-12`, with unlabelled slot and
payment dates elsewhere. `ReceiptDates`: (a) bill date, else the earliest
generic "Date", never expiry/mfg/due or unlabelled; (b) **midnight** — the
owner's noon collided with the SMS convention `OccurredAt` relies on, and the
owner chose midnight; (c) day-first, a leading four-digit year is ISO
(owner-confirmed); (d) refuse beyond +1 day / −1 year, no weaker date tried.

`ReceiptCorpusDateGradingTest` is **the first test to grade the extractor
against the real corpus** — on device, both receipts dated correctly; it skips
where the private store is not copied to the phone (KDoc has the adb steps).

### 2.5 Backup reminder on Home — `cf1a245`

One line with **Back up now** when there is no backup or none in over 7 days.
Clears when a backup lands, re-judged on every resume, never shown before the
date is read. Mutation-swept (5/5 red). On the owner's phone Home shows no line
(backed up today); **TESTING.md D11** covers the appearance, which needs 7 real
days.

## 3. What happened that the plan did not predict

1. **The owner's first backup failed "not this phone's phrase".** File dates
   (read with `run-as`, no contents) showed the vault's phrase wrap was written
   on 25 Aug at 16:26; `Download/` held two Recovery Kits, 17 Aug and 25 Aug
   16:26. The words typed were the 17 Aug install's. `verifyPhrase` did exactly
   its job. **Both kits are plaintext in `Download/`** — the owner will move the
   25 Aug words to a password manager and delete both files.
2. **D7's expectation was wrong in TESTING.md, and the app was wrong too** —
   BUG27. Android has no per-folder revoke screen; the row now says rename or
   delete the folder.
3. A **Samsung My Files Trash** entry held a valid backup the app had written:
   deleted by hand in My Files, not by the app (its deletes never pass through
   that Trash, and rotation only deletes past five).

## 4. Method notes — what the discipline caught

- **A mutation sweep that never ran.** The first date sweep reported thirteen
  identical "BUILD FAILED" with no failing test — `cmd` could not find
  `gradlew.bat`. Caught because every row looked the same; the harness now
  reports a harness error explicitly. **Identical results across different
  mutations are a harness bug until proved otherwise.**
- **Detekt failed the first restore gate** (`TooManyFunctions`, complexity).
  Stateless helpers moved to `VaultSessionSupport.kt` instead of a suppression
  in §7 code, and the two mutations whose anchors moved were re-run on the new
  code.
- **`hasVisualOverflow` lies with `softWrap = false`** — already documented in
  `Bug9_ControlLabelsNeverWrapTest`, and met again; the reminder's test uses
  `getLineEnd(0, visibleEnd = true)`, the project's measure.
- **`/*` inside a KDoc** (a shell glob in an example) opens a nested Kotlin
  comment. The grading test's example avoids it.
- **The uncached gate intermittently fails packaging `:feature:ocr`'s test APK**
  (twice). It passes alone; finish the gate without `--rerun-tasks`. Memory note
  `ocr-androidtest-packaging-flake`.
- **A commit message claimed a KDoc note that did not exist**; fixed with a
  follow-up commit (`3d9946f`), not an amend.
- The owner's phrase was never typed, read or seen by the agent. Recovery Kit
  files were located by **name and date only**.

## 5. Outstanding — the plan, in priority order

Each needs a stated plan and the owner's nod first.

1. ~~**Item 9, housekeeping**~~ **Done** (`0dc99f2`): `ReceiptGrading` is SPEC
   §12's metric in code; `ReceiptCorpusGradingTest` grades every real receipt on
   the device (Zepto 4/4, bigbasket 11/11, totals exact, dates and merchants
   right); `MINIMUM_REAL_RECEIPTS` 0 → 2; `:feature:ocr`'s test task re-runs
   when the manifest or the private store changes.
2. ~~**Item 7, merchant selection**~~ **Done** (`00d9715`): a PDF whose text
   names no shop has its rendered header recognised for the legal entity
   (bigbasket → "Innovative Retail Concepts Pvt Ltd"); merchant corrections are
   remembered as exact aliases (the `merchant_alias` table was never written
   before); **BUG28** — a merge undone by the next capture — fixed with it.
3. **Item 6, multi-page PDFs** — both corpus PDFs' page 2 is legal boilerplate
   (no items, no totals), so reading it changes nothing today; the real case —
   items continuing onto page 2 — has no sample. Recommended: defer until the
   owner captures one, as for item 8. **Owner: deferred** (2026-09-19).
4. **Item 8, top-aligned table cells** — only with a real receipt that needs it.
5. **Owner-only**: the private corpus repo on GitHub + a read-only token as a CI
   secret (then CI goes green); D6c and D10 on the phone (D10 needs a guest
   user); D11 after 7 days; move the phrase out of `Download/`.
6. **Deferred by decision**: ADR-0025 part 2 (nightly public-key backups) —
   X25519 source and independent golden vectors first; a "transfer" concept for
   Q22's wallet top-ups; phrase rotation (ADR-0009), which also becomes the
   answer for a restored vault whose phrase is suspected exposed.
7. **Stated gaps, not bugs**: no test restores a backup from an **older**
   schema; a crash during restore's image pass needs the words again;
   someone who onboarded on a new phone first must clear app data before
   restoring; `AttachmentBackup`'s post-write verification is unexercised
   (needs fault injection); `SafBackupFolder` has no automated test (D6 is its
   check — now passed on local and Drive).
