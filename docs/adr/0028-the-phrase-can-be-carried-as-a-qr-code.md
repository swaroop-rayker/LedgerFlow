# ADR-0028 — The recovery phrase can be carried as a QR code

- **Status:** **Accepted** and built (owner, 2026-09-20).
- **Date:** 2026-09-20
- **Deciders:** Swaroop (owner), lead engineer
- **Spec sections touched:** `SPEC.md` §7.2 (Recovery Kit), §7.3, §7.4, §9.6;
  `TESTING.md` D5, D10; `CLAUDE.md` §7.

## Context

Every route back into a vault costs 24 typed words: Recovery, restore
(ADR-0026), and "Back up now" (ADR-0025), which ADR-0027 now also uses once for
enrolment. The owner asked why the big providers never make people do this. The
answer is that they hold a spare key behind an account and a rate-limited
server, and this app has neither — so the secret must stay a generated 256-bit
value (`SPEC.md` §7.2).

**What can change is the carrier, not the secret.** 24 words, 64 hex characters
and a QR code are three encodings of the same number. A QR keeps every bit and
removes the typing.

## Decisions

| | Question | Chosen | Why |
|---|---|---|---|
| a | Library | **ZXing core** (`com.google.zxing:core`) | ~500 KB, pure Java, no Android dependencies, does both drawing and reading, and is the long-standing standard. ML Kit's barcode scanner adds several MB and only reads, so a QR *writer* would still be needed. Hand-rolling a QR encoder is the kind of format work that looks easy and has a specification for a reason. |
| b | Where the QR appears | **The Recovery Kit PDF only** | That file is already the plaintext copy of the phrase, written behind D-07's warning. A QR beside the words exposes nothing the file did not already expose. The `.txt` cannot hold one, and a separate PNG is one more plaintext copy to forget about. Deliberately **not** on screen at onboarding: it invites a screenshot, and a screenshot of the phrase lands in the gallery and often in cloud photo sync. |
| c | Which screens scan | **All three phrase screens** — Recovery, restore, "Back up now" | They already share `LfPhraseEntry`, so scanning is built once and cannot drift between them. Enrolment (ADR-0027) and every manual backup ask for the words, so the screen that most often asks benefits most. |

## What this does not change

- **The phrase is still 24 words and still 256 bits.** Scanning is an input
  method. Typing stays, always, on every screen — a camera that will not focus
  must never be the only way in.
- **No new permission.** `CAMERA` is already declared and pinned for receipt
  capture (`EXPECTED_MERGED_PERMISSIONS`), so the pin does not move. ZXing
  merges no manifest entries; if the pin moves, that is the guard working and
  the change stops (Law 6, ADR-0021).
- **No new key material, no change to derivation** (§7.2 stays byte-for-byte).

## Rules this imposes

1. **The scanned payload is validated as a phrase before anything else.**
   BIP-39 word list, then the checksum, before any key derivation — the same
   order §7 requires of typed words, so a mis-scan costs nothing.
2. **The camera frame never leaves the moment.** No frame is written to disk, no
   frame is logged, and nothing decoded is logged. `cacheDir` is not used
   (Law 5); the decoder reads the in-memory frame the analyser hands it.
3. **The scanner closes as soon as a phrase is decoded**, and the words go
   straight into the screen's `PhraseEntry`, which BUG30 already makes the
   screen forget when it is done.
4. **The payload is the 24 words**, space-separated, NFKD-normalised, with a
   short versioned prefix (`LFBK1:`) so a future format is distinguishable and
   a stray QR from elsewhere is rejected with a clear message rather than being
   half-parsed. A kit written before this ADR has no QR; nothing else changes.
5. **The Recovery Kit warning gains one sentence**: the file now contains a code
   a camera can read, so a photograph of the page is as good as the words. D-07's
   dialog says what the file is before the picker opens, and this belongs there.
6. **Accessibility**: the scan control is a labelled button, and the screen
   states that typing remains available. A scanner is unusable with TalkBack in
   practice, so the typed path is the accessible path and must never be demoted
   to a secondary action (§9.6, §7.4's rule about the only way forward).

## Amended 2026-09-26 — a kit after onboarding, and the scanner fixed (owner)

**The gap.** Rule (b) put the QR in the Recovery Kit PDF, and the kit was
offered **only at onboarding**. Every install that onboarded before this ADR,
the owner's included, had at most a `.txt` kit, so there was nothing to scan and
the whole feature was out of reach. Nobody had scanned a real kit (D5 never
ran), so nothing had shown it.

**Decision (owner): a new kit from "Back up now".** It is the one screen that
already takes the 24 words and proves them against this vault. After a
successful backup it offers **"Save Recovery Kit"**, a PDF with the QR, behind
the same D-07 dialog. Rejected: a separate Settings entry that asks for the
words just for this, which would be one more place handling the phrase.

- The verified words live in the ViewModel, **not in `UiState`**. They are
  forgotten when the kit is saved, on "Not now", when the words are edited, when
  another backup starts, and when the screen closes. A **failed write keeps
  them**, so another place can be tried (BUG30's rule).
- The D-07 dialog is now **one** composable, `LfRecoveryKitWarningDialog`,
  shared by onboarding and "Back up now". It finally says **rule 5's sentence**
  for the PDF (a camera reads the code, so a photo of the page is as good as the
  words), which the first version of the PDF path never showed.

**The scanner, fixed, found when the owner tried it:**
- **BUG35**: the button sat under the navigation bar, because the scanner
  replaces the screen's `LfScaffold` and handled no insets. The controls are now
  one inset card over a full-bleed viewfinder, with a line saying what to point
  at. The label became **"Type the words"**: "Type the words instead" clipped at
  font scale 2.0 (BUG9).
- **BUG34**: the first code of any kind latched the scanner shut, so a foreign
  code before the kit meant the kit was never read. This contradicts rule 3's
  intent and this ADR's own verification line ("a foreign code leaves it
  open"): it stayed open, and deaf. Every distinct code is now delivered once.
- **Checked and not a bug:** a camera plane whose last row omits its padding
  decodes as-is, because ZXing reads each row only up to the width. A padding
  "fix" was written, disproved by its own control test, and removed.
  `ScanFramesTest` keeps the case.

## Verification

- **`PhraseQrTest`** (JVM): the payload round-trips; a foreign QR reads as
  `NotOurs`; a `LFBK2:` code says it is newer rather than failing blankly; odd
  whitespace from a PDF viewer still scans; and `applyScan` fills the field,
  keeps the camera open for someone else's code, and replaces a half-typed
  phrase the way a paste does. The public BIP-39 test vector throughout, never
  a real phrase.
- **`RecoveryKitQrTest`** (device): the written PDF is rendered with
  `PdfRenderer` and decoded back to the exact words on the page — drawn and
  read by the same library, so the kit and the scanner cannot drift apart. The
  `.txt` kit is asserted to carry no code.
- **ViewModel tests on all three screens** (`RecoveryViewModelTest`,
  `RestoreViewModelTest`, `BackupNowViewModelTest`): a kit fills the words and
  closes the scanner, a foreign code leaves it open, and on restore, leaving
  forgets a scanned phrase exactly as it forgets a typed one (BUG30).
- **Not covered, and stated rather than implied:** no automated test drives a
  real camera frame through `LfPhraseScanner`'s analyser. Instrumentation
  cannot point a lens at a page, and a fake `ImageProxy` would test ZXing
  rather than this app. The decode path is exercised from the same library in
  `RecoveryKitQrTest`; what is unproven by machine is the camera plumbing —
  binding, permission, and stopping at the first code. `TESTING.md` D5 is
  where a person checks it.
- `TESTING.md` D5 gains the QR check on a real printout, and D10 gains "restore
  by scanning the kit instead of typing".
- **Added 2026-09-26:** `ScanFramesTest` (JVM): BUG34, plus a padded plane
  decoding as-is. `LfPhraseScannerScreenshotTest` (Robolectric, goldens at 1.0
  and 2.0): the instruction above the button, and the label within its button,
  measured by needed versus given width. `RecoveryKitWarningTest`: the PDF
  dialog mentions the QR, the text file's does not. Seven kit-offer cases in
  `BackupNowViewModelTest`, including every point where the words are forgotten
  and the failed write that keeps them. 8 mutations, each caught by its own
  test. **The camera plumbing is no longer unproven:** on the owner's phone a
  foreign test QR left the camera open, and the kit's test QR (the public test
  phrase) then filled all 24 words. `RecoveryKitQrTest` passed there too.
