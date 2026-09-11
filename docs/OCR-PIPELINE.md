# OCR pipeline — capture to approvable, step by step

Where P4 actually stands. `SPEC.md` §5.3 is the specification; this is the
build order and the honest status of each step.

Written at the point where capture works on real hardware and extraction has not
been started, because that is the boundary a new session most needs to find.

**Status at the time of writing**
- Branch `s12-testing-matrix-font-cta`, 92 commits ahead of `main`, no PR, **nothing pushed**
- Schema **v11** (`attachment`, `item_category_memory` — ADR-0023, ADR-0022)
- `preMergeCheck` green on both flavours
- Receipt corpus: machinery in place, **zero fixtures**

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

**The pipeline stops here.** The screen reports a count and a text preview and
says so in as many words: *"Line items are not extracted yet."*

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

## B. Extraction — **nothing built. This is the next chunk.**

Every step is arithmetic over `RecognizedElement`, so **every step is
JVM-testable off-device**. That is the entire reason `ReceiptTextRecognizer`
returns its own type instead of ML Kit's.

| # | Step | Notes |
|---|---|---|
| 6 | **Line reconstruction** — cluster elements into rows by y-centroid band | ML Kit's own line grouping is not usable: it merges runs across the wide gap between an item name and its price, which is the gap step 7 needs |
| 7 | **Column inference** — rightmost numeric run = amount, leftmost text run = name | |
| 8 | **Line classification** — HEADER / ITEM / TAX / DISCOUNT / SUBTOTAL / TOTAL / FOOTER / NOISE | Only `ITEM` counts toward §12's recall gate |
| 9 | **Merchant detection** — top-3 header lines → fuzzy match against `merchant` | `MerchantRepository.createOrGet` exists; the fuzzy match is a *suggestion*, never a gate |
| 10 | **Totals detection** — keyword set {TOTAL, GRAND TOTAL, NET AMOUNT, AMOUNT PAYABLE, बिल राशि} | |
| 11 | **Reconciliation** — `abs(Σitems + Σtax − Σdiscount − total) ≤ max(₹1, 0.5% of total)` | Unbalanced is **saveable**, not refused (§5.3, §5.4) |
| 12 | → `ExtractedTransaction` with `lines` | The payload is **already v2 and ready** (ADR-0022) |

---

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
| 18 | **GAP — `ReviewUiState.lines` is populated only from `review_draft_json` (user edits). Nothing seeds it from `extracted_json`.** An OCR candidate would arrive showing **zero lines**. | **must be fixed or B looks like it did nothing** |
| 19 | `LfLineItemEditor` renders and edits lines | **built**; has 1× / 2× goldens as of this session |
| 20 | Category suggestions from `item_category_memory` | table exists (v11); **no code** |
| 21 | Edits persist to `review_draft_json`, survive process death | **built** |

Step 18 is the cheapest way for the whole of section B to appear broken. Fix it
with B, not after.

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
  are all in; the private store does not exist yet.
- **§11's 2.5 s OCR budget has never been measured**, and two concurrent script
  passes is the thing most likely to breach it.

---

## The sequencing point that actually matters

`scripts/guard-corpus-order.sh` fails any commit touching both the corpus
manifest and `feature/ocr/**`, because ground truth written *after* the extractor
exists is contaminated by what the extractor happens to do.

Right now that guard is trivially satisfiable — there is nothing to contaminate.
**The moment section B starts, every fixture added afterwards is worth less**,
because the shape of the problem has been seen.

One real receipt in the private store before B begins is what turns §12's ≥90%
from an aspiration into a measurement. It costs about ten minutes:

1. Create the store — `../LedgerFlow-receipts`, or set
   `ledgerflow.receiptCorpusDir` in `local.properties`
2. Drop in the image **and** a hand-transcribed `.json`, transcribed *from the
   image* and never from any output
3. `./gradlew regenerateReceiptManifest`, commit the manifest

That also arms `ReceiptCorpusTest`'s CI check, which is gated on the manifest
listing at least one receipt.

---

## Shortest path to a receipt reaching the ledger

**Steps 6–15, plus 18.** Everything after that already works, because once the
row exists an OCR candidate is just a candidate.
