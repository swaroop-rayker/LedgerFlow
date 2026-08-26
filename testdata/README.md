# `testdata/` — the golden corpus

The parser's specification. `GoldenCorpusTest` runs every case here against the
**shipped** ruleset (`core/data/src/main/assets/parser_rules/v1.json`), not
against rules written for a test — so a green run means the app parses these,
not that the engine could be made to.

**The corpus only grows** (`CLAUDE.md` §11). When a real bank SMS or UPI
notification fails to parse, it becomes a permanent case here *before* the rule
that handles it is written. Deleting a case is how a fixed bug comes back.

## Layout

Every case is two files sharing a name:

```
testdata/sms/hdfc-upi-sent-multiline-real.txt     ← the message, verbatim
testdata/sms/hdfc-upi-sent-multiline-real.json    ← what the engine must extract
```

`.txt` holds the message body exactly as it arrives. For a notification that is
the flattened form the adapter produces — title, text, bigText and subText
joined with **newlines**, duplicates dropped. The newline is not cosmetic: it is
what lets a rule stop a merchant capture at the end of the title instead of
running through the chatter below it.

`.json` for an SMS:

```json
{
  "sender": "VM-HDFCBK",
  "expected": { "ruleId": "...", "amountMinor": 78800, "...": "..." }
}
```

and for a notification, additionally `"packageName"`, which is what the rule
matches on rather than `sender` (`SPEC.md` §5.2).

### Expectations

| Key | Meaning |
|---|---|
| `ruleId` | which rule must win. Asserting this rather than just the values catches a rule quietly stealing another's messages. |
| `amountMinor` | exact minor units. Never a decimal — Law 3. |
| `currency`, `direction` | always asserted when `expected` is present. |
| `merchantRaw`, `accountLast4`, `instrumentHint`, `referenceNo` | asserted only when present. |
| `occurredAtLocalDate` | `YYYY-MM-DD`. Compared as a **local date**, never an epoch: the parser reads the local date a bank message states, so an epoch would be a different number in another timezone and the fixture would only pass in IST. |

### Cases that must extract *nothing*

`"expected": null` asserts the engine produces `Unmatched`. These matter as much
as the positive cases and are easy to forget: an OTP, a promotional message and
a balance alert all arrive from the *same sender ID* as real transactions. A
parser that turns "123456 is your OTP" into a ₹123,456 payment is worse than one
that misses a payment.

## Real messages, and what gets redacted

A case marked with a `note` came from a real phone. Structure and wording are
verbatim — that is the whole point — but the **account last4 and the transaction
reference are substituted**, because this directory is committed and those two
are the identifying values. Nothing a rule matches on is ever changed: if a
redaction would alter what the parser sees, it is the wrong redaction.

Amounts, merchant names and dates are kept as they arrived. They are what make a
fixture realistic, and none of them identifies an account.

## Synthetic cases

Cases without a `note` are synthetic, modelled on published formats. They hold
the engine's shape and they are **not** the P2 exit criterion: `SPEC.md` §13 asks
for 50 SMS and 50 notifications, and §15.8 is explicit that they must be real. A
corpus of invented examples tests the rules against the assumptions that produced
them, which is circular. Real messages are the only thing that closes it.
