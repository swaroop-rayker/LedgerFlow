# ADR-0024 — OpenCV for photographed-page perspective correction, and the APK budget moves to 50 MB

- **Status:** Accepted
- **Date:** 2026-09-14
- **Deciders:** Swaroop (owner, instructed the addition and the budget raise), lead engineer
- **Supersedes / Superseded by:** none. **Amends ADR-0021's budget** (25 MB → 50 MB) and
  ADR-0005's "no image-processing dependency" posture for OCR capture only.
- **Spec sections touched:** `SPEC.md` §5.3, §11, §14, §15.4; `docs/OCR-PIPELINE.md` section B

## Context

`docs/OCR-PIPELINE.md` section B recorded perspective as the open photograph
problem: a receipt shot at an angle has rows that *converge*, and
`ReceiptGeometry.estimateSkew` removes a single slope, not convergence. Undoing
it needs the page's four corners and a four-point warp — image processing, which
the pipeline had deliberately avoided. The capture guide (`LfCaptureGuide`) was
the answer taken instead.

The owner asked for OpenCV alongside ML Kit and a raised size budget.

## What was measured

**Recognition, on the device** (SM-S721B, Android 16), a GST invoice page drawn
onto a dark surface at five angles, read by real ML Kit and the real extractor:

| angle | uncorrected | warped |
|---|---|---|
| mild keystone | 4 items, balanced | 4 items, balanced |
| strong keystone | 3 items, **unbalanced** | 4 items, balanced |
| side angle | 4 items, **no total** | 4 items, balanced |
| keystone + tilt | 2 items, **no total** | 4 items, balanced |
| extreme side angle | **0 items** | 4 items, balanced |

Warp time 18–33 ms per frame at 1800×2400 — negligible against §11's 2.5 s.

**Size, arm64-v8a release split** (R8 on, the artefact §11 names):

| build | APK | Δ |
|---|---|---|
| before (S13, after PDF text layer) | **18.18 MiB** | — |
| + `org.opencv:opencv:4.14.0` | **42.94 MiB** | **+24.76** |

The cost is one file: `libopencv_java4.so` is 24.66 MB, plus a 1.29 MB
`libc++_shared.so`. Both are **16 KB page-aligned** (PT_LOAD align 16384),
checked from the ELF headers, so Android 15+ page-size requirements are met.

Native libraries are stored **uncompressed** in the APK (AGP's default for
`minSdk` ≥ 23, so they can be memory-mapped). That is why the file grows by the
full size; the library deflates to about 9.2 MB, which is closer to what a Play
download costs. Packaging was **not** switched to compressed: it would apply to
ML Kit's and SQLCipher's libraries too and trade APK bytes for installed bytes.

The AAR's manifest declares **no permissions**; `EXPECTED_MERGED_PERMISSIONS` is
unchanged.

## Decision

1. **Add OpenCV 4.14.0** (the newest 4.x; 5.0.0 is a major-version API change
   and not needed for `imgproc`) to `:feature:ocr` only.
2. **Use it for one thing: `OpenCvPageCorrector`**, behind the
   `ReceiptImageCorrector` interface — edge detection, the largest plausible
   convex quadrilateral, a four-point `warpPerspective`. Applied to camera frames
   and imported photos, **not** to PDFs (a rendered page is already square, and
   digital PDFs are read from their text layer).
3. **Refuse rather than guess.** Any failure — native load, no four-cornered
   outline, an outline `PageQuad.isPlausible` rejects (not convex, under 20% of
   the frame, or the frame itself) — returns the input untouched.
4. **The warped image is the stored attachment**, keeping ADR-0023's "the image
   the pipeline saw".
5. **The APK budget moves from 25 MB to 50 MB** (CI measures `25 * 1024 * 1024`
   bytes, so MiB): 42.94 MiB measured, with ~7 MiB of headroom — about what the
   25 MB budget left over ML Kit.

## Consequences

- The app is about 2.4× its previous size on disk. The owner accepted this
  explicitly; it is recorded here so that "why is the APK 43 MB" has an answer.
- **The cheapest future reduction is a custom OpenCV build** with only `core`
  and `imgproc` — plausibly a few MB rather than 25 — at the cost of an NDK/CMake
  build of a third-party library in this repository. Not done now; it is the
  first thing to reach for if the budget is squeezed again.
- The seam is one `@Binds` line in `OcrModule`; removing OpenCV means binding a
  pass-through corrector and deleting the dependency.
- `OpenCvPageCorrectorTest` asserts both halves for each failing angle: the
  uncorrected read does **not** balance (if ML Kit ever copes on its own, the case
  goes red and has stopped proving anything) and the warped read does.

## Not decided here

- Whether a real thermal receipt photographed on the owner's surfaces gives the
  edge detector a clean enough outline. That is the corpus's question — the
  measurements above are synthetic pages on a plain dark surface.
- Shadow removal and adaptive thresholding: not added. ML Kit binarises
  internally, and thresholding a clean image usually makes it read worse.
