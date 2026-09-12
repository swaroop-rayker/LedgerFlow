# Session log — S13, OCR candidate path and the first real receipt

Ten commits on `s13-ocr-candidate`, **nothing pushed**. Schema **v11, unchanged**
— the whole session added no migration.

Written standalone rather than as §1 of a `KICKOFF-S14.md` because the next
session's briefs were handed over directly rather than committed. The two are
listed in §8 so nothing is lost if they were not kept.

**Verified state at `3b19cb6`**

| | |
|---|---|
| `preMergeCheck` | green, both flavours |
| Unit tests | **836**, 0 failures (`:feature:ocr` 116 of them) |
| Instrumented `:core:database` | **93**, 0 failures — full v1→v11 chain, `PreMigrationGuard`, backup round-trip |
| Instrumented `:core:data` | **314**, 0 failures |
| Instrumented `:feature:ocr` | **15**, 0 failures |
| Guards | schema, versionCode, corpus-order all pass |
| Device | Samsung SM-S721B, Android 16 |

---

## 1. What the session set out to do, and what it actually did

It began as **section C of `docs/OCR-PIPELINE.md`** — steps 13–15, getting a
photographed receipt as far as the Inbox. That landed, along with E's step 26
and the two cleanup obligations around it.

Then the owner pointed the finished thing at **a real Food Bazaar GST invoice**
and it returned **2 line items out of 6 and a bill total of ₹75.00 on a
₹1,075.46 purchase**. Everything after §3 is the consequence of that one
receipt, and it is the more valuable half of the session.

---

## 2. Section C — a receipt becomes a candidate

`e55b0f8`, `858c7b6`, `9046946`.

- **Step 13** — `DefaultAttachmentRepository` seals the image into
  `filesDir/attachments/`, `.tmp` → fsync → rename, `sha256` over the
  plaintext so the same receipt twice stores once.
- **Step 14 turned out to be already built.** `DuplicateMatcher` has never had
  a source check, so routing OCR through the same insert a bank SMS uses made
  §3.1's cross-source dedupe cover it by construction. The cost was a refactor,
  not a feature: `PendingCandidateWriter` now owns insert-with-dedupe and both
  entry points call it. Detekt demanded the same extraction independently
  (`TooManyFunctions`) — right answer, wrong reason.
- **Step 15** — `recordOcrCandidate`, differing from `recordParseOutcome` in
  exactly one parameter, and that difference is structural rather than a source
  check: a message capture left a raw row wanting a `parse_status`; a receipt
  did not.
- **Step 26** — `markApproved` sets `attachment.entry_id` in the same
  transaction as the status change. Attempted unconditionally: `raw_ref_id`
  holds an attachment id for a receipt and a raw row's id for a message, the
  `UPDATE` matches by primary key, and for a message it affects nothing.
- **The purge unlinks.** `AttachmentDao` had documented this obligation since
  v11 and nothing honoured it — `ON DELETE CASCADE` took the row and left the
  bytes, and nothing else enumerates that directory, so every missed file was
  leaked *invisibly*. Paths are read before the delete; afterwards returns
  nothing, which is the bug that looks like it works.
- **Settings — "Receipts — N images, M MB"**, ADR-0023's answer to unbounded
  growth, behind the `Warning` treatment. Currently the only way to delete an
  image.

### ADR-0023 amended, twice

1. **The local seal is a key *derived from* the DEK, not the DEK.** The ADR said
   "under the DEK" and nothing retains the DEK after unlock — `VaultSession`
   destroys it the moment SQLCipher has it, and SQLCipher's copy sits behind
   `cipher_memory_security` in a way a JVM-heap copy would not. So
   `HKDF(dek, info = "ledgerflow-attachment-local-v1")`, zeroed on close. A heap
   compromise now yields receipts, not the ledger. Owner's decision.
   `AttachmentKey` lives outside `KeyDerivation` deliberately — different blast
   radius, different change rule — and its committed vector was **computed with
   an independent RFC 5869 implementation**, not recorded from this code.
2. **Recognised large, stored small** — see §4.2.

---

## 3. The real receipt: three modelling errors

`39e2090`. Diagnosed by feeding the classifier **hand-typed clean text**, which
reproduced all three with no OCR in the loop — so these were never a recogniser
problem.

| Failure | Cause |
|---|---|
| 5 of 6 items lost | The totals block was delimited by the first row of *any* summary kind. An Indian GST invoice prints `S GST 9% / C GST 9%` under **every item**, so the first product's own tax rows closed the shopping four lines into a thirty-line bill. |
| Total read as ₹75.00 | The DISCOUNT list had `TOTAL SAVINGS`; the bill says `TOTAL SAVING`. One letter, and last-totals-row-wins did the rest. |
| Tax would double-count ₹171.36 | NET AMT already contains the GST. `147.46 × 1.18 = 174.00` = the printed figure, and the six items sum to the total exactly. |

Fixes: only SUBTOTAL/TOTAL ends the item block; savings language moved to the
informational set (it is neither the total **nor a part** — as a DISCOUNT it
subtracted per-item discounts already applied, leaving the bill ₹75 short);
`Reconciliation` computes both the additive and inclusive readings and takes
the one that closes, the same self-validating principle `ReceiptColumns` uses
for `unit × quantity`.

---

## 4. The photograph, not the arithmetic

The extractor's arithmetic was in good shape and its handling of an actual
photograph was not. Four changes, in the order they were worth doing.

### 4.1 Skew corrected before banding — `15aec8e`

Banding assumed rows were horizontal. A hand-held capture is rotated, and the
drift across a wide bill exceeds the band tolerance before it reaches the
amount column: the row splits into a name with no price and a price with no
name, and both then classify wrongly. This is the likeliest cause of the
garbled merchant on the real receipt.

§5.3 asks for image deskew; `estimateSkew` does it in arithmetic instead —
median of slopes between adjacent runs that plainly share a line, banding on
`centerY − slope·centerX`. No dependency, still JVM-testable.

**Working range measured at about 10°.** Past that two runs on one line stop
overlapping, no pair qualifies, and the estimator returns zero — the page is
read as square, which is the honest failure rather than a wrong answer.

### 4.2 Recognition at 2560, storage at 1600 — `9077088`

Recognition ran on the *stored* size, which leaves a 42-character thermal line
at roughly 12–14 px on a phone photo — at or under where ML Kit is reliable, on
exactly the text §12's recall gate measures.

**§11's 2.5 s OCR budget measured for the first time since P0:**

```
 672×1600  ->  656 ms, 230 runs
1075×2560  ->  711 ms, 230 runs
```

2.5× the pixels for 8% more time, 3.5× headroom. `RecognitionBudgetTest` prints
it rather than only asserting a ceiling.

Not claimed: that the extra pixels raise item recall. Both sizes found 230 runs
on a synthetic page, which is what a crisp render should do. Settling it needs
a real photograph in the corpus.

### 4.3 Capture guide — `ab22786`

`LfCaptureGuide`, in `:core:designsystem` because `CLAUDE.md` puts hand-rolled
Canvas primitives where the Roborazzi harness is. That paid immediately:
written inside `:feature:ocr` it shipped **two theme faults**, only one visible
on the device in front of me — a scrim from the *active* palette lightens the
surround in light theme, and `onAccent` corner marks are near-black in dark
theme. It paints over a camera image, which has no theme, so both now come from
the dark palette in both. Goldens are **byte-identical** across themes, which
is the assertion rather than a coincidence.

It deliberately does **not** gate the shutter.

### 4.4 Runs that cannot be text are dropped — `0bdf583`

I was going to skip this, reasoning that the medians would absorb texture.
**Measuring showed the opposite** — a median only protects while real print is
the majority, and on the cloth-backed real receipt (195 runs for ~60 lines) it
is not: names acquired leading speckle, and the page scale fell from 20 px to 9.

Threshold-free rule: a run with neither a letter nor a digit cannot be a name
or an amount. Area-weighted median was tried first and traded the fault rather
than fixing it — weighting by ink lets a 3× header dominate a short bill.

---

## 5. Two defects found that were not the task

- **`ImageDecoder` is API 28, `minSdk` is 26.** Photo import crashed on Android
  8.0/8.1 with `NoClassDefFoundError`. **Pre-existing** — confirmed with
  `git stash`, not assumed. Fixed with a `BitmapFactory` path that downsamples
  during the decode, as the other path does.
- **The capture screen contradicted itself after a save** (`9046946`): the
  caption still read "nothing saved yet" above a card saying "Saved to your
  Inbox", with the Save control still live. Found on the device; a preview has
  no "after" state to render, so the state machine is pinned in tests instead.

---

## 6. `preMergeCheck` has a hole — the most important finding here

**It runs `lintSmsFullDebug` on `:app` only, never on a library module.**
Running lint across every module found **six real errors it can never fail on**.
One is fixed (§5); five remain:

| Module | Error |
|---|---|
| `:core:crypto` | `StrongBoxUnavailableException` referenced below API 28 — **§7 Danger Zone**, untouched pending the owner |
| `:feature:budget` | `MissingPermission` on notification post |
| `:feature:ingest` | `MissingPermission` on notification post |
| `:core:designsystem` | `NonObservableLocale` in a composable |
| `:feature:categories` | `NonObservableLocale` in a composable |

This repository has recorded "a gate that silently does nothing" five times
already (`ReceiptCorpusTest`'s KDoc lists them). This is the sixth.

---

## 7. Method notes — what the discipline actually caught

**Mutation sweeps found four vacuous tests of my own**, and fixing them was a
real part of the work rather than bookkeeping:

- the texture test used two stray runs where twenty are needed to move a mean
- the sparse-page test used runs that did not qualify as pairs at all, so the
  guard and its absence both returned zero
- the clamp test sheared an ordinary bill to 60°, where the estimator quietly
  finds nothing, so it asserted nothing
- `theHsnAndUomRow_isNotAnItem` passed whether or not the keyword existed

The geometry for two of those is now *derived* — a pair survives the overlap
rule only while `slope × dx ≤ (1 − minOverlap) × height`, which body text never
satisfies at steep angles.

**One claim corrected mid-session.** The garbled merchant was attributed to row
banding; it cannot be, because every join in the pipeline uses a space and
`MALLNDRAPURAM` has none. It is ML Kit's own element text.

**Device gotcha:** `screencap` wedged to a uniformly black image while the
device was awake and rendering — it looked exactly like a rendering bug in the
new overlay. A second screen captured black too, which is what proved it was
the capture. A sleep/wake cycle fixed it.

---

## 8. Outstanding, in priority order

1. **Fuzzy keyword matching.** OCR read `GST` as `6ST`; keyword matching is
   exact substring, so the row fell through as an item. §12 already accepts
   Jaro-Winkler for item *names*. **Largest known remaining source of wrong
   lines.**
2. **Close the lint hole** (§6), including the `:core:crypto` one.
3. **The corpus fixture needs its image.** The hand transcription is staged at
   `../LedgerFlow-receipts/pending/food-bazaar-gst-thermal.json`, written before
   any fix was made for that receipt. Drop the photograph beside it, move both
   up a level, run `./gradlew regenerateReceiptManifest`. A provenance question
   comes with it: this receipt is from the internet, not the owner's shopping,
   and `testdata/receipts/README.md`'s privacy argument assumes otherwise.
4. **Step 27** — `item_category_memory`. Table and upsert exist; nothing calls
   `record`. Last step in section E.
5. **The phrase-sealed image copy beside the `.lfbk`** (ADR-0023). Specified,
   unbuilt: a restore today returns rows and no images.
6. **No date detection.** A receipt photographed days later lands on the wrong
   day until corrected.
7. **Merchant selection on a real header** — `FOOD BAZAAR` did not win, cause
   undiagnosed. Needs the image from (3).

---

## 9. A note on the cloud-OCR question

The owner asked whether to send receipts to a cloud OCR service, given how hard
this is locally. The answer that settled it was evidence rather than principle:
**all three catastrophic failures in §3 were parser bugs reproduced on clean
typed text.** Cloud OCR would have returned the same words and the bill would
still have read ₹75.00. ML Kit read that receipt well.

ADR-0021 already rejected cloud OCR on Law 6's *substantive* half, and that
remains the position. The honest counter-argument — that document-AI APIs
return *structured* line items and would replace the parser rather than the
recogniser — is recorded here so it does not have to be rediscovered. It should
be re-opened, if at all, with corpus numbers rather than one bad screenshot.
