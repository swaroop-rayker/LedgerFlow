# ADR-0014 — `PagingData` reaches the domain interface

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** none
- **Spec sections touched:** `SPEC.md` §5.5, §9.3, §11; `CLAUDE.md` §3, §8

## Context

The Ledger tab had no read path at all — `LedgerScreen.kt` was a hardcoded empty
state, which is why a saved entry never appeared anywhere (BUG10). Building that
read path forces a decision the repository has so far avoided, because Paging 3
had no callers until now.

Two rules in `CLAUDE.md` point in opposite directions, and both are stated
without qualification:

> §8 — Never load a full ledger into memory. Paging 3, always.

> §3 — `:core:domain` depends on `:core:model` + `:core:common` only.

`PagingData`, `Pager` and `PagingSource` are `androidx.paging` types. Putting a
list read on `LedgerRepository` therefore puts an AndroidX coordinate into the
one module that is deliberately kept free of them. Not putting it there means
the ledger's own repository cannot answer "what is in this book", which is the
single most obvious question to ask it.

Worth naming precisely, because it is the crux: **`paging-common` is not an
Android library.** It is a Kotlin/JVM artifact — `PagingData`, `Pager`,
`PagingSource`, `PagingConfig`, `LoadState` and nothing from `android.*`. It
runs in a plain JVM unit test. `paging-runtime` (the `LiveData`/`RecyclerView`
half) and `paging-compose` are the Android-facing artifacts, and neither is
needed below `:feature:*`. So the question is not "does Android leak into the
domain" — it does not — but "does a third-party coordinate of any kind belong
on a domain port".

`:core:domain` already depends on `kotlinx-coroutines` for `Flow`, which is the
same shape of dependency: a third-party streaming abstraction on a domain port,
admitted because writing our own would be worse. That precedent is the reason
this is a close call rather than an obvious no.

Law 2 constrains every option equally and is not in tension with any of them:
whatever the shape, the read takes a `ledger: LedgerType`, there is no overload
that omits it, and there is no variant returning both books.

## Options considered

### Option A — `PagingData` on the domain interface

| | |
|---|---|
| Summary | `LedgerRepository.observeEntries(ledger): Flow<PagingData<LedgerListItem>>`. `:core:domain` gains `api(libs.androidx.paging.common)`. `:core:data` builds the `Pager` over the Room `PagingSource`; `:feature:ledger` adds `paging-compose` for `collectAsLazyPagingItems()`. |
| Cost | One JVM-only coordinate on `:core:domain`, and an explicit carve-out written into `CLAUDE.md` §3 so the next reader does not treat it as drift. No effect on APK size beyond Paging itself, which §11 already budgets for. |
| Risk | The carve-out is quotable. "Domain already depends on AndroidX" is an argument someone will one day use to justify adding `androidx.work` or `androidx.room` there, which would be a genuinely different thing. Mitigated by naming `paging-common` specifically in the build file and in §3, not "AndroidX". |

### Option B — domain stays paging-free; the feature talks to a paging port

| | |
|---|---|
| Summary | `LedgerRepository` gets no list read. A separate interface — in `:core:data`, or a new `:core:paging` — exposes the pager, and `:feature:ledger` depends on it directly. |
| Cost | A second port for the same data, and a feature that reaches past `:core:domain` for its main read. §3's letter is kept (features may depend on any `:core:*`), but in practice every feature to date sees `:core:domain` and nothing below it, so this is a new edge in the graph. |
| Risk | The ledger's read path stops being visible on the repository that owns the ledger. Someone adding a filter or a date range later has to discover that ledger reads live somewhere other than `LedgerRepository`, and the likely outcome is a second, unpaged read appearing on the repository "for convenience" — which is exactly the §8 violation Paging is here to prevent. |

### Option C — wrap Paging in a LedgerFlow-owned abstraction

| | |
|---|---|
| Summary | Domain declares our own paged-source type; `:core:data` implements it over Paging 3; `:feature:ledger` adapts it back to `PagingData` for `LazyPagingItems`. |
| Cost | A file of glue whose only job is to re-type AndroidX as ours, plus a second file to convert it back one layer up. |
| Risk | The wrapper has to faithfully re-expose load state, refresh, invalidation and retry, or the list silently stops updating after an approval — a failure that looks exactly like BUG10 coming back. We would be hand-maintaining the least interesting part of a library we are already depending on, and the abstraction buys nothing unless Paging is ever swapped out, which nothing suggests. |

## Decision

**`LedgerRepository` exposes `observeEntries(ledger: LedgerType):
Flow<PagingData<LedgerListItem>>`, and `:core:domain` takes an `api` dependency
on `androidx.paging:paging-common` — that artifact only.**

The deciding argument is that Option A's cost is a *documentation* cost and
Options B and C's costs are *correctness* costs. §3's purpose is that the domain
layer stays pure Kotlin and unit-testable off-device; `paging-common` does not
threaten either property, so honouring §3's letter here would be paying for its
spirit twice while making the ledger's own read path harder to find (B) or
hand-rolling cache invalidation (C).

It is close between A and B, and the reason is worth recording: B is defensible
and costs nothing today. What decided it against B is the second-order effect —
a repository that owns the write path but not the read path invites an unpaged
`observeAll()` to appear beside `approve()`, and §8's rule has no enforcement
mechanism the way Law 2 does.

## Consequences

**What this makes easy.** One port for the ledger, mirroring
`observeRecentCombos`: same class, same `ledger` parameter, same "no variant
returns both books" rule. Filters, a date range and search (all P1/P3 scope)
extend the existing signature rather than needing a second read surface. The
Room `PagingSource` means the ledger is never fully materialised, so §11's
"0 frames > 16.6 ms during Ledger scroll" target is reachable by construction
rather than by later rework.

**What this makes hard.** `:core:domain` unit tests that touch this method now
need `paging-common` on the test classpath, and asserting over a `PagingData`
requires `androidx.paging:paging-testing` (`asSnapshot()`), which is a
test-scoped addition to the catalogue. A `PagingData` is opaque without it —
there is no supported way to read items out of one by hand, and inventing one
would be testing our own reflection rather than the query.

**What we now have to maintain forever.** The carve-out in `CLAUDE.md` §3, and
the distinction between `paging-common` (admissible in `:core:domain`) and
`paging-runtime` / `paging-compose` (not). The comment in
`gradle/libs.versions.toml` carries the same warning at the point someone would
change it.

**What would make us revisit this.** If a second AndroidX coordinate is ever
proposed for `:core:domain`, that is the trigger — not to relitigate Paging, but
because the carve-out was meant to stay a carve-out. Likewise if Paging 3 gains
an Android-only dependency in `paging-common`, this ADR's premise is gone and
Option C becomes the honest answer.

## Verification

- `core/domain/build.gradle.kts` names `libs.androidx.paging.common` explicitly,
  with a comment stating that `paging-runtime` and `paging-compose` are not
  admissible there. A module-graph change is visible in review as a build-file
  diff, which is the enforcement level this warrants.
- `LedgerIsolationTest` (`:core:database`) already fails the build on any query
  naming `ledger_entry` without a bound ledger, and on any statement touching
  both views. The paged queries added under this ADR read `debit_entries` and
  `credit_entries`, one statement each, and are covered by it.
- `Bug10_SavedEntryAppearsInLedgerTest` (`:core:data`, instrumented) approves an
  entry and asserts it appears in its own book **and is absent from the other**,
  through `observeEntries` — the API this ADR introduces, not the DAO beneath
  it.
