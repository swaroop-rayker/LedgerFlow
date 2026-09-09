# `testdata/receipts/` — the OCR corpus

The OCR gate's specification, and the **only part of it that is public**.

`SPEC.md` §12 sets P4's exit at **≥90% item recall and ≥95% precision, reported
per receipt**. This directory holds the machinery for that number. It does not
hold the receipts.

## The split, and why

| | lives in | holds |
|---|---|---|
| **Public** — this repo | `testdata/receipts/` | `README.md`, `manifest.json`, the guards and tests |
| **Private** — a separate store | see below | the receipt images **and** their expected-output JSON |

**The images are private for the obvious reason.** An Indian retail receipt
carries a card tail, often the customer's mobile number, a loyalty id, an
invoice number that links to the shop's own record of the purchase, and
sometimes a QR encoding all of it. This repository is public.

**The expected-output JSON is private for the less obvious one.** It *is* the
shopping list — item names, quantities, prices, merchant, date — structured and
greppable. As a record of what someone actually bought it is arguably more
revealing than the photograph, and "it's only the ground truth" is exactly the
reasoning that would put it in the public repo by accident.

**Redaction was rejected, and this is the alternative.** `testdata/README.md`'s
rule for the SMS corpus is that a redaction must not change what the parser
sees — swapping an account tail `1234` → `9902` leaves the regex an identical
shape. No such move exists for an image: every pixel edit is a change to the
recogniser's input, a black box can create artifacts a real receipt never has,
and a corpus that has quietly drifted from reality is §16 Q15 in a new costume.
So nothing is redacted. The corpus is real and it is not published.

**What stays public is what a reader would actually want to check**: the metric
and its definition, the provenance rules, the ratchet, and the manifest that
makes the corpus's size and composition auditable without disclosing a single
line item.

## Where the private store lives

Resolved in this order, first hit wins:

1. `LEDGERFLOW_RECEIPT_CORPUS` environment variable — an absolute path. **This
   is what CI sets**, from a checkout of the private repository.
2. `ledgerflow.receiptCorpusDir` in `local.properties` (gitignored).
3. `../LedgerFlow-receipts/`, a sibling of this checkout.

Inside it, one pair per receipt, sharing a base name:

```
2026-09-12-kirana-thermal.jpg     ← the image
2026-09-12-kirana-thermal.json    ← what the extractor must produce
```

### When the store is absent

`ReceiptCorpusTest` **skips with a printed reason locally, and fails in CI.**
That asymmetry is deliberate. A fresh clone by someone without the private store
should still build; but a gate that silently does nothing is the failure this
repository has now recorded five times, so the environment that is *supposed* to
have the corpus treats its absence as an error rather than a pass.

## The fixture format

```json
{
  "image": "2026-09-12-kirana-thermal.jpg",
  "provenance": "real",
  "capture": {
    "surface": "thermal POS",
    "conditions": "handheld, indoor light, slight curl",
    "safeBecause": "paid cash; no loyalty number given; no card tail printed"
  },
  "merchant": "…",
  "currency": "INR",
  "billTotalMinor": 47300,
  "lines": [
    {
      "position": 1,
      "name": "TOMATO 1KG",
      "quantityMilli": 1000,
      "unitPriceMinor": 4000,
      "totalMinor": 4000,
      "kind": "ITEM"
    },
    { "position": 2, "name": "CGST 2.5%", "totalMinor": 300, "kind": "TAX" }
  ]
}
```

- **All money is `Long` minor units** (Law 3). Never a decimal, in a fixture or
  anywhere else.
- `kind` is `ITEM | TAX | DISCOUNT | UNALLOCATED`, matching `line_item.kind`.
  **Only `ITEM` lines count toward recall** — a missed tax line is a different
  defect from a missed purchase.
- `capture.safeBecause` is **required on every `real` fixture.** It is the
  receipt analogue of the SMS corpus's "what was substituted": since nothing is
  redacted, the fixture has to say why it was safe to keep whole. Writing that
  sentence is the moment you notice a printed phone number.

## Adding a receipt — the order matters more than the content

**Ground truth is written and committed before the extractor ever sees that
receipt.** This is the same rule as `CLAUDE.md` §11's "a real message that fails
to parse becomes a fixture *before* the rule that handles it is written", and it
is enforced: `scripts/guard-corpus-order.sh` fails a commit that touches a
receipt fixture and `feature/ocr/**` source together.

1. Photograph the receipt. Prefer a capture protocol that makes it safe by
   construction — pay cash, decline the loyalty scan — over one that needs
   explaining.
2. Transcribe the expected output **by hand, from the image**.
3. Commit both to the private store.
4. Regenerate `manifest.json` and commit that here.
5. *Then* work on the extractor.

**Never bootstrap the ground truth from the extractor's own output.** It is by
far the fastest way to write a fixture and it silently blesses whatever the
extractor already does as truth, which inflates recall by an unknown amount.
That is the one shortcut that makes the whole corpus worthless, and it is
undetectable afterwards.

## The corpus grows; it is not built in an afternoon

Three receipts a week beats thirty in one sitting: the corpus ends up drawn from
real shopping rather than one day's backlog, and the extractor is never tuned
against a set it has already seen in full.

Until the corpus reaches **25 graded receipts / 300 `ITEM` lines**, the ≥90%
figure is **provisional** — a ratio over eight receipts is a measurement with
error bars wide enough to drive through, and calling it a met gate would be the
same category of claim as an unmarked synthetic corpus. `SPEC.md` §12 says so;
`ReceiptCorpusTest` prints the current standing on every run so the state is
never in doubt.

### Diversity floor

A corpus of thirty receipts from one supermarket is one receipt tested thirty
times. Before the gate can be called met it must span: thermal POS (including a
faded one), an A4 GST invoice, a handwritten kirana slip, a curled or crumpled
sheet, poor light, `₹` and `Rs.` and `INR` as currency markers, **at least one
Devanagari-bearing bill**, a long roll with 40+ lines, and one PDF.

Also **one Kannada and one Malayalam** receipt. ML Kit has no model for either
script (ADR-0021), so those two are not there to be recognised — they are there
to measure what that costs. Indian receipts print items and amounts in Latin
almost universally, so the honest expectation is that item recall on them is no
worse than the rest of the corpus. If it is worse, that is the evidence a second
engine would need, and without them nobody would ever have it.

Plus negatives: a photograph that is **not** a receipt must extract nothing
rather than hallucinate a bill — the image analogue of the SMS corpus's
`"expected": null` cases, and just as easy to forget.
