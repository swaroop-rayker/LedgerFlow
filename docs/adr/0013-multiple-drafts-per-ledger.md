# ADR-0013 — A ledger may hold many unsaved entries

- **Status:** Accepted
- **Date:** 2026-08-18
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** **Supersedes D-06** (`SPEC.md` §2.5, §6.1.2).
- **Spec sections touched:** `SPEC.md` §2.5 (D-06), §6.1, §6.1.2

## Context

D-06 settled `draft_entry` as **one row per in-flight entry, with uniqueness
scoped by `UNIQUE(ledger, editing_entry_key)`**. That allowed exactly one
new-entry draft per book and one edit-draft per existing entry.

Its reasoning was explicitly about avoiding two failure modes, and it named the
cost of the alternative:

> Unbounded drafts would accumulate into a list nobody curates.

That is a real risk and the constraint was a reasonable answer to it on paper.
In use it produced the failure it was designed to prevent. Reported by the
owner after using the build:

> I cannot have multiple instances of entries … it can store only one entry or
> pending entry, i cannot enter many expense entries leave it pending.

Because the slot was unique, starting a second entry did not create a second
draft — it *resumed the first*. So the second entry could not exist, and the
first appeared to have consumed it. D-06's own argument against the singleton
("starting a second entry silently destroys the first … that is precisely BUG6")
applied to the scoped constraint too, one level up: it did not destroy the
first draft, but it made the second one unrepresentable, which reads the same
from the outside.

The owner also connected it to a use case the spec already anticipates:

> especially needed when SMS+notification integrations and auto entry comes in.
> multiple SMSs might arrive and need to be queued or stacked like the
> notifications in our phone.

## Options considered

### Option A — keep D-06, make resume more obvious

Leave the constraint and label the resume clearly, so the behaviour stops
reading as data loss. Cheapest. Rejected because it explains the limitation
rather than removing it: the user still cannot have two entries in progress,
which is the thing they asked for.

### Option B — drop the constraint, no new surface

One line of migration. Rejected on its own: it walks straight into the pile-up
D-06 correctly identified. Drafts would exist and be unreachable.

### Option C — drop the constraint **and** ship the stack

Remove the unique index, and show one book's unsaved entries newest-first on
the entry screen with Open / Discard / Start another.

## Decision

**Option C.** Schema v3 drops `UNIQUE(ledger, editing_entry_key)`, and the entry
screen grows an "Unsaved (n)" stack.

The decisive point is that D-06's objection was never really an argument for a
constraint — it was an argument for a **surface**. Drafts accumulate unseen only
if nothing shows them. Once they are visible and individually discardable, the
uniqueness index is buying nothing that the list does not buy better, and it is
costing the ability to have two entries in progress.

Two things D-06 got right are kept:

- **Retention.** The 30-day orphan sweep on app open is what stops "many drafts"
  becoming "drafts forever". It matters *more* now, not less.
- **One edit-draft per entry.** You cannot be editing the same entry twice. That
  is now a repository rule (`findForEntry`) rather than an index, because a
  partial index — `WHERE editing_entry_id IS NOT NULL` — is not expressible
  through Room's `@Index`, and a full one is the constraint this ADR removes.

A draft's identity becomes its **id** rather than its slot. The form holds the
id it was given and passes it back on every debounced write; without that, the
dropped index would let a form deposit a fresh draft every 300 ms.

**The form no longer auto-resumes.** It opens empty and the stack offers what is
unsaved. With several half-finished entries, "resume the most recent" is the app
guessing which one the user meant. BUG6's guarantee is unchanged in substance —
nothing typed is ever lost — but its shape moves from "the form comes back
filled in" to "your work is there and one tap away", which is what the owner
asked for: *"come back and click on that instance and save it."*

**This is manual drafts only, and that separation is deliberate.** The SMS and
notification queue is `pending_transaction` and the Inbox (§5.1, §5.2), landing
at P2. §5.4 already keeps the two apart: one gates a commit and is what Law 1
is about, the other recovers unsaved typing and gates nothing. Merging them
would put automated captures in a table the user can edit freely, which is
exactly what Law 1 forbids. The stack's *shape* is intended to be reused by the
Inbox; the table is not.

## Consequences

**What this makes easy.** Several entries in progress at once — the reported
need. A place to put the same interaction for the Inbox at P2. Switching ledger
no longer has to choose between discarding the outgoing form and resuming a
different one; it parks the current draft in its book's stack.

**What this makes hard.** Drafts can now genuinely pile up, and the sweep plus
the stack are the only things preventing it. If the stack is ever removed or
buried, the pile-up D-06 warned about returns immediately.

**What we now have to maintain forever.** The stack surface; the repository-level
one-edit-draft-per-entry rule that used to be an index; and the id round-trip
through the form, without which the debounce writes a new row per tick.

**What would make us revisit this.** Users routinely accumulating drafts they
never return to, such that the stack becomes clutter rather than a queue. The
remedy then is a shorter retention or a prompt, not the constraint back.

## Verification

- `MigrationV2ToV3Test` — a second DEBIT draft is rejected before the migration
  and accepted after it, so the test fails if the index is left behind *or* if
  the schema changes without the migration. Every seeded draft's payload
  survives, asserted on content rather than row count (§8).
- `DraftRepositoryInstrumentedTest` — saving without an id adds a draft; saving
  with one updates it; `observe` is newest-first and never crosses books (Law 2);
  `findForEntry` locates the edit-draft; the 30-day sweep spares fresh rows.
- `EntryViewModelTest` — the form opens empty with the draft offered in the
  stack, and opening it restores field-for-field.
- `Bug6_DraftSurvivesProcessDeathTest` — after the whole graph is rebuilt from
  disk, the unsaved entry is in the stack and opening it restores it.

## Amendment — 2026-08-19: the surface is a shelf, not a stack

The decision above is unchanged; this records that the word "stack" throughout
it describes a shape that did not survive contact with the device.

"Stacked like notifications" was the request and the first build took it
literally: a vertical list above the form. The flaw is structural rather than
aesthetic — on this screen the drafts are not the content, the form is, and a
vertical list displaces the form downward in proportion to the draft count,
which is worst precisely when the user has the most parked work. The shelf is a
`LazyRow` of fixed-width cards: one row of height regardless of count, newest
first, with a partially visible next card doing the job the scrollbar would
otherwise have to.

Two consequences worth recording because both were bugs first:

- `Start another` moved into the shelf **header** (a `FlowRow`). In the row it
  was unreachable in the exact state that needs it — with one draft it *was*
  the peeking card — and at font scale 2.0 its label clipped to "Start anoth",
  which is BUG9.
- **Opening a draft must not re-date it.** `updated_at` orders the shelf, so
  a read that writes would mean inspecting the shelf reorders the shelf. The
  ordering has to mean "what I last worked on", not "what I last looked at".

Read every "stack" below as "shelf". `SPEC.md` §6.1.2 carries the current
description.
