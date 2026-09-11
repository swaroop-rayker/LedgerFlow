# OCR pipeline — capture to approvable, step by step

Where P4 actually stands. `SPEC.md` §5.3 is the specification; this is the
build order and the honest status of each step.

Written when capture worked on real hardware and extraction had not been
started; updated when extraction landed. The boundary a new session most needs
to find is now the one between **B and C** — a bill is read and nothing is yet
written down.

**Status**
- Branch `s12-testing-matrix-font-cta`, 97 commits ahead of `main`, no PR, **nothing pushed**
- Schema **v11** (`attachment`, `item_category_memory` — ADR-0023, ADR-0022)
- `preMergeCheck` green on both flavours
- Receipt corpus: machinery in place, **zero fixtures**
- **Sections A and B are built; step 18 is fixed. C is next** (steps 13–15),
  plus the two loose ends in E.

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

### Still open in B

- **No date detection.** §5.3's pipeline does not list one and the review
  screen falls back to the capture time, so a receipt photographed days later
  lands on the wrong day until the user corrects it.
- **No fuzzy merchant match.** Step 9 emits `merchantRaw`, which is that
  field's contract. §5.5's Jaro-Winkler ≥ 0.88 suggestion is a review-time
  surface and needs the merchant table — and the same metric is what §12's
  recall grading needs for item names, so it is one implementation serving two
  callers.
- **§11's 2.5 s budget is still unmeasured.** Extraction itself is
  microseconds; the two concurrent script passes remain the thing at risk.

## C. Becoming a candidate — nothing built; schema is ready

| # | Step | Status |
|---|---|---|
| 13 | Encrypt the image under the DEK → `filesDir/attachments/`, write the `attachment` row | table exists (v11); **no code** |
| 14 | **Cross-source dedupe** (§3.1) — a receipt and the bank SMS for the same payment must yield **one** candidate | **no code**; easy to forget and it is a stated rule |
| 15 | Write `pending_transaction`: `source = OCR`, `raw_ref_id = attachment.id`, `extracted_json` carrying the lines | **no code**; `DefaultRawIngestRepository` is the pattern to follow |

`attachment.file_path` is **relative** to `filesDir/attachments/` and `sha256` is
over the **plaintext** — both load-bearing, both documented on the entity.

---

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

## E. Approval — built; two loose ends

| # | Step | Status |
|---|---|---|
| 22 | `ApprovePendingUseCase` → `ApproveTransactionUseCase` (Law 1's single writer) | **built** |
| 23 | `ApprovalRequest` already carries `lineItems: List<NewLineItem>` | **built** |
| 24 | Remainder written as `UNALLOCATED` so the parts always sum to the whole | **built** |
| 25 | Writes `ledger_entry` + `line_item`, updates rollups at line grain (ADR-0018) | **built** |
| 26 | **Loose end:** set `attachment.entry_id` on the newly created entry | |
| 27 | **Loose end:** record the filing into `item_category_memory` so the next bill suggests it | |

---

## Cross-cutting, still open

- **Attachment images beside the `.lfbk`** (ADR-0023). Metadata already travels
  in the backup; the phrase-sealed image file does not. Restore must report the
  count it could not find.
- **Purge must unlink attachment files.** `ON DELETE CASCADE` takes the row and
  leaves the bytes; nothing else enumerates that directory, so a missed file is
  leaked permanently. Documented as an obligation on `AttachmentDao`, not yet
  honoured by `PurgeDeletedEntriesUseCase`.
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

**Steps 13–15.** 6–12 and 18 are done; everything after 15 already worked,
because once the row exists an OCR candidate is just a candidate. What is left
is the attachment write, cross-source dedupe, and the `pending_transaction`
row — and per `CLAUDE.md` §7's Danger Zone, whatever writes them opens the
vault itself or it lies: a background caller gets a throw from
`requireDatabase()`, the throw lands in a `runCatching`, and the action
reports success having done nothing (BUG13).
