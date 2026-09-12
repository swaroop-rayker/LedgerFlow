# OCR pipeline — capture to approvable, step by step

Where P4 actually stands. `SPEC.md` §5.3 is the specification; this is the
build order and the honest status of each step.

Written when capture worked on real hardware and extraction had not been
started; updated as each section landed. The boundary a new session most needs
to find is now **inside E** — a receipt becomes a candidate and the user can
approve it, and the approval does not yet link the image to the entry it
created.

**Status**
- Merged to `main` from `s12-testing-matrix-font-cta` (fast-forward, history
  kept). **No commit count here on purpose** — the previous revision of this
  line carried one and it was wrong within the hour.
- Schema **v11** (`attachment`, `item_category_memory` — ADR-0023, ADR-0022)
- `preMergeCheck` green on both flavours; the instrumented suites green on
  the physical device — `:core:database` 93 tests (the full v1→v11 migration
  chain, `PreMigrationGuard`, and `BackupRestoreRoundTripTest` including
  `backup_wipe_restoreFromPhraseAlone_reproducesEveryRowExactly`) and
  `:core:data` 307 tests (the attachment store and the OCR dedupe among them)
- Receipt corpus: machinery in place, **zero fixtures**
- **A receipt reaches the ledger.** Sections A–E are built apart from step 27
  (category memory). Verified end to end on the device: scan → extract →
  seal → candidate → review shows the lines → approve.
- Settings has ADR-0023's "Receipts — N images, M MB" with a `Warning`-gated
  bulk delete, which is currently the only way to remove an image.

---

## A. Capture — built, verified on device

| # | Step | Where |
|---|---|---|
| 1 | Entry point: centre action dial → "Scan a receipt" | `LedgerFlowShell.kt`, `Destination.ScanReceipt` |
| 2 | Source: camera, gallery (`PickVisualMedia`), or SAF (`OpenDocument`, image **or** PDF) | `OcrCaptureScreen.kt` |
| 3 | Decode + downscale to ≤1600 px long edge; PDF first page rasterised onto **white** | `ReceiptImageLoader.kt` |
| 4 | Recognise: ML Kit Latin **+** Devanagari, run concurrently, merged with an overlap rule | `ReceiptTextRecognizer.kt` |
| 5 | → `RecognizedPage` — text runs **with bounding boxes** | — |

**Verified on the owner's device against a real receipt: 90 text runs**, and the
preview showed the merchant address from the top of the bill. That last detail is
evidence rather than decoration: the preview sorts by `centerY` and takes the
first runs, so the header appearing means the geometry is vertically sane — which
is the property every step in section B is built on.

`RecognizedPage` is where the device-dependent half ends. Everything after it
is arithmetic, which is section B.

### Already paid for, and worth not re-deciding

- ML Kit is **bundled** (ADR-0021). +12.35 MB on the arm64 release split, almost
  all of it one native library. Budget moved 15 → 25 MB on that measurement;
  current split is 17.80 MB.
- Release declares `INTERNET`, from Google's telemetry uploader rather than any
  model download. **Law 6 is amended, not broken** — recognition is on-device
  *structurally*, because the model is in the APK. `OcrRunsWithoutNetworkTest`
  proves it in aeroplane mode.
- **Kannada and Malayalam are impossible.** ML Kit ships five script models:
  Latin, Chinese, Devanagari, Japanese, Korean. Probed, not assumed. Hindi *is*
  Devanagari, so it is already covered.
- CameraX's APK cost is **currently ~0 because R8 strips it** — nothing
  referenced it until the capture screen. That figure will move.

### Two defects already found and fixed here, so they are not re-introduced

Both came from the capture screen being a `verticalScroll` Column, and the first
fix made the second visible:

1. **SurfaceView in a scrolling container renders black.** Its buffer composites
   in window coordinates and ignores scroll offset and parent clip. Fixed with
   `ImplementationMode.EMBEDDED`.
2. **A child of a scrolling Column is measured with `maxHeight = Infinity`**, so
   the viewfinder's scale transform came out degenerate and magnified a sliver of
   the texture over the whole view — the preview read as one flat colour that
   tracked the scene. Measured: **stddev 0.00** across 9,072 samples before,
   51.68 after. Fixed with `Modifier.aspectRatio()`, which bounds the height
   regardless of the parent.

---

## B. Extraction — **built, JVM-tested, verified on device**

Every step is arithmetic over `RecognizedElement`, so every step is a JVM
test — 69 of them, milliseconds, no device and no ML Kit. That is the entire
reason `ReceiptTextRecognizer` returns its own type.

| # | Step | Where |
|---|---|---|
| 6 | **Line reconstruction** — y-centroid banding at 0.6x the page's median glyph height | `ReceiptGeometry.rows` |
| 7a | **Cell segmentation** — split each row at gaps wider than 1.0x that height | `ReceiptGeometry.cells` |
| 7b | **Column inference** — rightmost amount, leftmost name, `qty x rate` when it closes | `ReceiptColumns` |
| 8 | **Line classification** — HEADER / ITEM / TAX / DISCOUNT / SUBTOTAL / TOTAL / FOOTER / NOISE | `ReceiptLineClassifier` |
| 9 | **Merchant detection** — the tallest plausible header row | `MerchantHeader` |
| 10 | **Totals detection** — the keyword set; the **last** match wins | `ReceiptExtractor.detectTotal` |
| 11 | **Reconciliation** — parts against total, within `max(₹1, 0.5%)` | `Reconciliation` (`:core:domain`) |
| 12 | → `ExtractedTransaction` with `lines` | `ReceiptExtractor.extract` |

### The decisions worth not re-litigating

- **Both geometric constants are derived, not chosen.** Rows band at 0.6
  because adjacent printed lines are at least 1.2 glyph heights apart, so 0.6
  is the midpoint — the largest tolerance that cannot merge two rows at
  minimum leading and the smallest that absorbs a baseline wobble within one.
  Gutters split at 1.0 because a word gap is 0.3–0.5 and a column gutter is
  several. The page scale is the **median** run height; a mean is dragged up
  by a 3x shop name until sixteen rows band into six.
- **A quantity and a unit price are believed only when `unit x qty`
  reproduces the printed amount exactly.** No tolerance. A row laid out
  `NAME MRP RATE AMOUNT` therefore keeps its amount and loses its description,
  which is the right way round — the amount *is* the line.
- **`ReceiptNumbers` is integer-only.** `"45.05".toDouble() * 100` is
  4504.999…, so Law 3's failure would enter the ledger right here. Half its
  tests are rejections — a percentage, a mobile number, a GSTIN, a date, a
  misread `4O.00` — because each parses under a loose rule and each yields a
  believable line item.
- **Three classifier ordering traps**, all real on Indian bills: `SUB TOTAL`
  contains `TOTAL`; `TOTAL SAVINGS` contains `TOTAL` and means a discount;
  `TOTAL QTY: 14` contains `TOTAL` and would file the bill as ₹14 unless the
  administrative check runs *before* the keyword sets. Tender rows are the
  expensive one — `CASH` and `CHANGE` add roughly twice the bill to the item
  sum.
- **`Reconciliation` lives in `:core:domain`, not here**, because the review
  screen has to recompute it on every keystroke and features may not depend on
  features. Nothing about it is persisted: it is a pure function of the lines
  and the total, both already in `extracted_json`.
- **Keyword matching is exact substring first, then one glyph's worth of
  doubt.** OCR read `S GST 9%` as `S 6ST 9%` on the owner's bill and the row
  fell through as an item — one of the two "items" that reached the Inbox on a
  six-item receipt. `ReceiptKeywords.matches` now falls back to a
  word-boundary-anchored Jaro-Winkler window (`:core:domain`'s `JaroWinkler`,
  one implementation for this, §5.5's merchant suggestion and §12's recall
  grading). **The threshold is §5.5's own 0.88, unchanged**, and the fix that
  made the case reachable was a *longer keyword* — the TAX set now lists the
  spaced `S GST` / `C GST` forms an Indian invoice actually prints, so the
  comparison is five characters (0.8933) rather than three (0.7778).
  Two further clauses are load-bearing and were each chosen from a measured
  false-positive count over ~75 real Indian retail item names: **equal length**
  (without it `REFINED` matches `REFUND` at 0.8944, and `REFUND` decides the
  direction of the whole receipt) and **at most one differing character**
  (without it `CASHEWS` matches `CASHIER` at 0.8857). All three together admit
  zero new wrong lines; any two of them admit between 1 and 12.
- **A short keyword stays exact, and 0.88 is what makes it so.** No single
  substitution can reach 0.88 below four characters — the worst case at three is
  0.8222 — so there is no length constant to maintain. Which is necessary
  rather than tidy: at three characters the score ranks a *different word*
  above the real misread (`GET`/`GST` = 0.80, `6ST`/`GST` = 0.7778), and a
  one-substitution rule with no threshold admits `TEA` as `TEL`, `TIL` as
  `TIN`, `BAT` as `VAT` and `CURD` as `CARD`.
- **`NotPossible` is not `Unbalanced`.** A bill whose total could not be read
  has not failed a check. Reporting a delta against zero would state the sum
  of the items as a discrepancy — specific, believable, and wrong.

### Verification, and what it does *not* cover

**Mutation-swept.** Sixteen deliberate breaks, with *which* tests went red read
rather than assumed. Four turned nothing red; three of those were real holes
and now have tests — the TENDER keywords only mattered on a slip with no total
at all, `detectTotal`'s last-wins only on a bill printing two totals, and
merchant-by-height only when the name is not also the first line. The fourth,
the `%` rejection, is genuinely redundant with the digit check, and the code
now says so instead of implying it is load-bearing.

**On device** (Android 16, physical): a receipt imported through SAF gave 51
ML Kit runs, and the pipeline read the merchant, four items, ₹694.46 and
`Balanced`, with the `2 x 165.00 = 330.00` row closing on ML Kit's *real*
boxes rather than only on hand-laid ones. At font scale 2.0 the names wrap and
the amounts do not clip (BUG9).

**That page was text rendered on white.** It is a smoke test. It is not corpus
material, it must never be added to the corpus, and it says nothing whatever
about thermal paper — which is the substrate the gate is actually about.

### The photograph, not just the arithmetic

Four changes came out of the owner's first real receipt, in the order they
were worth doing:

1. **Skew is corrected before banding.** `estimateSkew` reads the page's
   dominant text slope off the recognised boxes — the median of slopes between
   adjacent runs that plainly share a line — and rows band on
   `centerY - slope * centerX`. §5.3 asks for image deskew; this does it in
   arithmetic, so it needs no dependency and stays JVM-testable. **Working
   range measured at about 10°**: past that two runs on one line stop
   overlapping, no pair qualifies, and the estimator returns zero rather than
   a wrong number.
2. **Recognition runs at 2560, storage stays at 1600.** Recognition used to
   run on the stored size, which left a 42-character thermal line at ~12 px on
   a phone photo. **§11's 2.5 s budget is measured for the first time**:
   656 ms at 1600, 711 ms at 2560 — 2.5× the pixels for 8% more time.
3. **A capture guide** in the viewfinder (`LfCaptureGuide`, in
   `:core:designsystem` so it inherits the screenshot gate). Improving the
   input beats correcting it, and it keeps captures inside the range skew
   correction can fix.
4. **Runs with neither a letter nor a digit are dropped.** The real receipt
   was shot on woven cloth and returned 195 runs for ~60 lines; measured, that
   surplus polluted item names and dragged the page scale from 20 px to 9.

### Still open in B

- **No date detection.** §5.3's pipeline does not list one and the review
  screen falls back to the capture time, so a receipt photographed days later
  lands on the wrong day until corrected.
- **Perspective.** A page shot at an angle also converges, and undoing that
  needs a four-point warp — corners, and therefore image processing. The
  capture guide is the answer taken instead.
- **Merchant selection on a real header.** `FOOD BAZAAR` did not win merchant
  detection on the owner's receipt and the cause is undiagnosed; the garbled
  `MALLNDRAPURAM` token is ML Kit's own, since every join in this pipeline
  uses a space. Needs the image.

## C. Becoming a candidate — **built**

| # | Step | Where |
|---|---|---|
| 13 | Seal the image under a DEK-derived key → `filesDir/attachments/`, write the `attachment` row | `DefaultAttachmentRepository` |
| 14 | **Cross-source dedupe** (§3.1) — a receipt and the bank SMS for one payment yield **one** candidate | `PendingCandidateWriter` |
| 15 | Write `pending_transaction`: `source = OCR`, `raw_ref_id = attachment.id`, `extracted_json` carrying the lines | `RawIngestRepository.recordOcrCandidate` |

### Step 14 was already built, and that is the interesting part

Nothing OCR-specific was written for it. `DuplicateMatcher` has never had a
source check — deliberately, and §0's source-agnostic rule is why — so routing
the receipt's candidate through **the same insert a bank SMS uses** made
cross-source dedupe cover it by construction.

What that cost was a refactor rather than a feature: the insert-with-dedupe
moved out of `DefaultRawIngestRepository` into `PendingCandidateWriter`, which
both entry points now call. Reimplementing it for receipts would have passed
every test written against it and still produced two rows on a device, because
§3.1's whole point is that one payment can now be observed **three** ways.

The two callers differ in exactly one parameter, and it is structural rather
than a source check: a message capture left a row in `sms_raw` or
`notification_raw` that wants a `parse_status`; a receipt did not.
`recordOcrCandidate_leavesRawRowsUntouched` pins that a receipt stamps neither
table — a write that silently affects zero rows being the shape §7 keeps
warning about.

### The key the image is sealed with is *not* the DEK

ADR-0023 said "under the DEK" and nothing retains the DEK after unlock —
`VaultSession` destroys it the moment SQLCipher has it, and SQLCipher's copy is
protected by `cipher_memory_security` in a way a JVM-heap copy would not be.
The ADR is **amended**: `HKDF(dek, info = "ledgerflow-attachment-local-v1")`,
retained for the database handle's lifetime and zeroed on close. A heap
compromise now yields the receipts and not the ledger. Owner's decision; the
reasoning and the two alternatives are in ADR-0023.

### Three facts about the stored file

- **`file_path` is relative** to `filesDir/attachments/`. Absolute would be a
  BUG1/BUG2 with a long fuse — it resolves on the machine that wrote it and
  nowhere else, and the symptom is a receipt that vanished rather than an error.
- **`sha256` is over the plaintext**, so the same receipt scanned twice stores
  once and the second scan reaches step 15 with the first one's id — coming
  back `AlreadyPending` instead of producing a twin.
- **The write is `.tmp` → fsync → rename**, §7's backup-writer discipline. It
  does *not* decrypt-and-verify before the rename, and that difference is
  deliberate: a `.lfbk` is the user's last copy, a receipt image has the entry
  beside it and a phrase-sealed copy in the backup tree.

### Still open in C

- **Nothing writes the backup copy yet.** ADR-0023's phrase-sealed image
  beside the `.lfbk` is specified and unbuilt, so today a restore returns rows
  and no images. The honest-degradation path exists — a missing file reads as
  null rather than crashing — but the count is not yet reported anywhere.
- **The purge still does not unlink.** `ON DELETE CASCADE` takes the
  `attachment` row and leaves the bytes, and nothing else enumerates that
  directory. `AttachmentDao.pathsForEntry` exists for exactly this and
  `PurgeDeletedEntriesUseCase` does not call it yet.
- **No Settings surface.** ADR-0023 promises "Receipts — N images, M MB" with
  a manual bulk delete, which is also the only way a user could remove an
  image today.

## D. Review — mostly built, with one specific gap

| # | Step | Status |
|---|---|---|
| 16 | Inbox lists the candidate | **built**, and source-agnostic — an OCR candidate needs no new code here |
| 17 | Review screen renders amount / merchant / date / category | **built** |
| 18 | `ReviewUiState.lines` and `itemised` seeded from `extracted_json` as well as from `review_draft_json` | **fixed** — `ExtractedLinesMapping`, `ReviewSeedsExtractedLinesTest` |
| 19 | `LfLineItemEditor` renders and edits lines | **built**; has 1× / 2× goldens as of this session |
| 20 | Category suggestions from `item_category_memory` | table exists (v11); **no code** |
| 21 | Edits persist to `review_draft_json`, survive process death | **built** |

**Three things inside step 18 that are load-bearing.**

**Both fields or neither.** `newLineItems` returns nothing while `itemised` is
false, so seeding the lines alone gives a receipt a form that looks
single-item and an approval that silently drops every line it extracted.

**Only `ITEM` lines reach the form.** `ReviewLine` carries no kind and
`newLineItems` builds every line as `ITEM`, so a seeded `TAX` line would
commit tax to `line_item` as a purchase and item-grain analytics would count
it as shopping. Nothing is lost: every extracted line stays in
`extracted_json` whatever its kind, and the shortfall becomes an
`UNALLOCATED` line at approval (§5.4). **A review editor that understands
`kind` is the natural follow-up**, and it is the only thing between an
extracted `TAX` row and the screen.

**A line may lose its quantity, never its total.** The editor derives the
total as `unit x qty` and shows it read-only so the three numbers cannot
disagree; a receipt's three numbers can. When they do, the quantity collapses
to one and the total is kept.

---

## E. Approval — built; **step 27 is the only one left**

| # | Step | Status |
|---|---|---|
| 22 | `ApprovePendingUseCase` → `ApproveTransactionUseCase` (Law 1's single writer) | **built** |
| 23 | `ApprovalRequest` already carries `lineItems: List<NewLineItem>` | **built** |
| 24 | Remainder written as `UNALLOCATED` so the parts always sum to the whole | **built** |
| 25 | Writes `ledger_entry` + `line_item`, updates rollups at line grain (ADR-0018) | **built** |
| 26 | Sets `attachment.entry_id` on the newly created entry | **built** — in `markApproved`'s transaction |
| 27 | Records the filing into `item_category_memory` so the next bill suggests it | **not built** |

Step 26's link is attempted **unconditionally** and that is not a source
check: `raw_ref_id` holds an attachment id for a receipt and a raw row's id
for a message, the `UPDATE` matches by primary key, and for a message it
affects nothing. `approvingAMessageCandidate_linksNothing` pins that.

Step 27 is a suggestion mechanism, not a filing one — `item_category_memory`
exists (v11) with its DAO's upsert already written, and nothing calls
`record`. It is the last thing between a second bill from the same shop and
categories pre-filled from the first.

## Cross-cutting, still open

- **Attachment images beside the `.lfbk`** (ADR-0023) — **the biggest one
  left.** Metadata already travels in the backup; the phrase-sealed image file
  does not, so a restore today returns rows and no images. The honest-
  degradation half exists (a missing file reads as null rather than crashing)
  but nothing reports the count.
- ~~**Purge must unlink attachment files.**~~ **Done.** Both purge statements
  read the paths before the delete and unlink after it succeeds, and
  `AttachmentLifecycleInstrumentedTest` covers the dangerous direction too:
  a purge that matches no rows must destroy nothing.
- **Corpus: zero fixtures.** Structure, guards, manifest task and the CI check
  are all in; the private store does not exist yet. **The extractor now
  exists, so the window below has closed** — every fixture written from here
  on is written by someone who has seen what the extractor does. That does not
  make them worthless, because transcription is still by hand and still from
  the image, but the first one is no longer free of it.
- **§11's 2.5 s OCR budget has never been measured**, and two concurrent script
  passes is the thing most likely to breach it.

---

## The sequencing point that actually matters

`scripts/guard-corpus-order.sh` fails any commit touching both the corpus
manifest and `feature/ocr/**`, because ground truth written *after* the
extractor exists is contaminated by what the extractor happens to do.

**That window has closed.** Section B shipped before the first fixture did.
The guard still holds per commit — a fixture and an extractor change may not
arrive together — and what remains is the discipline that was always the real
rule: **transcribe from the image, never from the extractor's output.**
Bootstrapping ground truth from a dump and correcting what you happen to
notice is fast, inflates recall by an unknown amount, and is undetectable
afterwards.

Creating the store is still ten minutes and is still what turns §12's ≥90%
from an aspiration into a measurement:

1. Create the store — `../LedgerFlow-receipts`, or set
   `ledgerflow.receiptCorpusDir` in `local.properties`
2. Drop in the image **and** a hand-transcribed `.json`, transcribed *from the
   image*
3. `./gradlew regenerateReceiptManifest`, commit the manifest

That also arms `ReceiptCorpusTest`'s CI check, which is gated on the manifest
listing at least one receipt. Until it happens, the corpus gate is a test that
passes by doing nothing.

## Shortest path to a receipt reaching the ledger

**There is no longer a gap in the path.** A receipt reaches the ledger today:
capture, extract, seal, dedupe, candidate, review, approve.

What remains is quality rather than reachability — steps 26 and 27, the
backup copy, and the purge's unlink. The one with teeth is **26**: until
approval sets `attachment.entry_id`, every image stays unlinked, which means
the entry has no receipt to show and the purge has nothing to find when it
eventually learns to unlink.
