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

## Verification

- `QrPhrasePayloadTest` (JVM): round-trips the payload, rejects a foreign QR, a
  wrong prefix, a truncated phrase and a phrase whose checksum fails — using the
  public BIP-39 test vector, never a real phrase.
- `RecoveryKitQrTest`: the PDF contains a QR that decodes back to the exact
  words written on the page — drawn and read by the same library, so it also
  pins that the kit and the scanner agree.
- A device test scans a rendered QR through the analyser path, proving the
  camera pipeline decodes what the kit draws.
- `TESTING.md` D5 gains the QR check on a real printout, and D10 gains "restore
  by scanning the kit instead of typing".
