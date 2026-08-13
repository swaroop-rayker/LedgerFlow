# ADR-NNNN — <short decision title>

- **Status:** Proposed | Accepted | Superseded by ADR-MMMM | Rejected
- **Date:** YYYY-MM-DD
- **Deciders:** <who actually decided>
- **Supersedes / Superseded by:** <ADR refs, or none>
- **Spec sections touched:** <e.g. SPEC.md §6.1, §7.2>

## Context

What forces are at play? What constraint makes this a decision rather than an obvious default? Link the relevant `SPEC.md` section and any Law from `CLAUDE.md §2` that bears on it.

State the problem without presupposing the answer. If the honest framing is "we can have at most two of these three", say so here.

## Options considered

### Option A — <name>

| | |
|---|---|
| Summary | |
| Cost | binary size, build time, maintenance burden, native deps |
| Risk | what breaks, and how loudly |

### Option B — <name>

*(same shape)*

At least two options, honestly argued. An ADR with one option and a rubber stamp is a changelog entry, not a decision record. If an option was rejected quickly, say why in one line rather than omitting it — the reader's next question is always "did you consider X?".

## Decision

The chosen option, stated as a single unambiguous sentence.

Then the reasoning: the specific argument that decided it, not a restatement of the comparison table. If the decision is close, say it is close — a future reader needs to know whether reopening it is cheap or expensive.

## Consequences

**What this makes easy.**

**What this makes hard.** Be specific. Every real decision has a cost; an ADR that lists only benefits is hiding one.

**What we now have to maintain forever.** Migrations, test fixtures, golden vectors, committed schemas.

**What would make us revisit this.** The concrete trigger — a measurement, a platform change, a phase boundary. "If X exceeds Y, reopen."

## Verification

How do we know this decision is being honoured six months from now? Name the test, the guard script, or the CI job. A decision with no enforcement decays into a suggestion.
