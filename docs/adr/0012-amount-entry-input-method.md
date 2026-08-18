# ADR-0012 — Amount entry uses the system keyboard, not an in-app keypad

- **Status:** Accepted
- **Date:** 2026-08-18
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none. Reverses an implementation decision made during S8 and recorded only in a commit message and KDoc.
- **Spec sections touched:** `SPEC.md` §5.4, §9.4

## Context

S8 shipped manual entry with an in-app keypad (`LfKeypad`) rather than the
system IME. The reasoning at the time was recorded, and it was not bad
reasoning:

1. **Law 3.** A keypad appends digits right-to-left in minor units — "1", "12",
   "125" become ₹0.01, ₹0.12, ₹1.25 — so the amount is a `Long` from the first
   keystroke and there is never a decimal string for something to parse into a
   `Double`. The obvious IME implementation, `text.toDouble() * 100`, cannot
   represent 0.10 exactly and silently loses a paise on some values.
2. **`KeyboardType.Decimal` is a hint.** Some OEM keyboards ignore it and serve
   a full QWERTY, so "the user will get digits" is not a guarantee.
3. **Speed.** §5.4 targets four taps for a repeat expense, and the IME costs a
   focus animation and half the screen before the first digit lands.

The owner used it and rejected it: *"why not just open device keyboard and then
I can just type the amount (in number) rather than pressing the number button"*.

## Options considered

### Option A — keypad with right-to-left accumulation (what shipped)

Typing `125` means ₹1.25. This is the dominant pattern in **payment** apps —
Cash App, Venmo, the send-money flows in GPay and PhonePe — where amounts are
usually round and keyed in fresh, and where malformed input is structurally
impossible.

### Option B — system keyboard with natural typing

Typing `125` means ₹125.00 and `125.50` means ₹125.50. This is the dominant
pattern in **ledger and budgeting** apps, where the user is usually
transcribing an exact figure off a receipt or a bank message rather than
composing a payment.

### Option C — both, behind a setting

Rejected in one line: two amount-entry models is two code paths, two sets of
tests, and a preference nobody asked for. Amount entry is the single most-used
control in the app; it should have one behaviour.

## Decision

**Option B. Amount entry uses the system keyboard, and `LfKeypad` is deleted.**

The deciding argument is what kind of app this is. LedgerFlow is a ledger, not
a payments app. Its own visual direction is Toshl-adjacent (§9.1) and its
primary flows are transcription — a receipt, an SMS, a UPI notification. In
that context an accumulator is a trap: `125` meaning ₹1.25 is surprising every
time until you learn it, and the thing you are copying is almost never a round
number.

**Law 3 is preserved by construction, not by avoiding the keyboard.**
`MoneyFormat.parse` converts the typed text to minor units with integer
arithmetic only: split on the decimal separator, fold each side into a `Long`,
combine with the currency's ISO-4217 exponent. No `Double` and no `BigDecimal`
on the path. `"8415.79".toDouble() * 100` is `841578.9999999999`, which floors
one paise short — that failure is pinned by a golden test rather than avoided
by declining to use an IME.

Argument 2 inverts rather than disappearing. Because the keyboard cannot be
trusted, the parser discards anything that is not a digit or a separator
instead of rejecting it: `"12ab.5"` is 12.50, `"₹1,250.00"` is 1250.00, junk is
zero. That tolerance is what makes an untrusted keyboard safe, and it is
cheaper than owning a keypad.

Argument 3 was partly a measurement error in the spec. "≤4 taps" was never
literally achievable including the digits, whichever input method is used; what
it meant is four taps *after* the amount. §5.4 is corrected to say so rather
than being quietly broken.

## Consequences

**What this makes easy.** One fewer component to maintain, size and test at
2.0× font scale. Paste works. Backspace, caret placement and text selection are
the platform's, correct in every locale and with every accessibility service.
Line-item amounts and the entry amount can share one parser, so two fields on
one screen cannot behave differently — which they did before this.

**What this makes hard.** The field holds raw text, so there are two values
where there was one: `amountText` and the derived `amountMinor`. They are not
two sources of truth — the `Long` is derived in one place — but the temptation
to "just reformat the text as they type" has to be resisted permanently,
because it moves the caret out from under the user's thumb on every keystroke.
The KDoc says so at the point of temptation.

**What we now have to maintain forever.** `MoneyFormat.parse` and its vectors,
including the truncation rule (extra decimals are truncated, not rounded, so
the formatter never rewrites a number the user is still typing) and the
digit cap that stops a long paste folding past `Long.MAX_VALUE` into a negative
amount.

**What would make us revisit this.** Dogfooding showing that transcription is
*not* the common case — that most entries are round amounts keyed fresh — would
be a real argument for the accumulator. Observed, not hypothesised.

## Verification

- `MoneyFormatTest` — parse vectors: whole numbers, decimals, exponent handling
  for JPY (0) and BHD (3), truncation, junk rejection, overflow, and a
  round-trip against `plain`.
- `EntryViewModelTest` — typing `125` yields ₹125.00; the field keeps exactly
  what was typed; clearing returns to zero.
- `AmountInputLayoutTest` — BUG9's one-line contract on the amount at font
  scale 2.0, and the field reporting raw text without rewriting it.
