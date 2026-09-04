# Data Visualisation & Analytics — plan and design

**Status:** Proposed, 2026-09-02. Supersedes nothing; expands `SPEC.md` §5.6 and §5.7 rather than replacing them.

`SPEC.md` is what to build and `CLAUDE.md` is how. This document sits between
them for one subject: it holds the **complete catalogue** of analytics surfaces
— the ones §5.6 and §5.7 already specify plus the ones added to differentiate
this app — with the phase each lands in, the data each needs, and the honest
dependency that gates it. `SPEC.md` §5.6 carries the summary table; this file
carries the reasoning.

Read §1 before §3. The catalogue only makes sense once it is clear *why* some of
these surfaces are buildable here and nowhere else.

---

## 1. What actually differentiates this app

Not a chart type. Anyone can draw a donut. Three **structural** properties of
this codebase make certain analytics possible here and impractical in a
conventional expense tracker:

**1. Item grain, with quantity and unit price.** `line_item` carries `name`,
`normalized_name`, `quantity_milli` and `unit_price_minor` (ADR-0018). A
mainstream tracker knows *merchant and amount*; this one can know what was in
the bag and what it cost per kilogram. The schema for this is **complete
today** — `EntryFormLines.kt` already captures the full triple on a manual
itemised entry. What is missing is volume, which OCR supplies at P4.

**2. Provenance on every row.** `ledger_entry.source` records SMS /
NOTIFICATION / OCR / MANUAL, and `pending_transaction` retains confidence,
dedupe keys and suppressed duplicates. Ingest is the product, so the app can
show the user how well its own capture is working. Nothing that syncs from a
bank feed has this question to answer.

**3. A refusal to net the books.** Law 2 forbids any figure combining DEBIT and
CREDIT. Competitors are built around a single net-worth number; a design that
visibly keeps two books is one they cannot copy without abandoning their own
framing.

Everything in Family B below flows from (1), Family C from (2), Family D
from (3). Family A is the conventional core, and it is table stakes — done well
it is not a differentiator, and done badly it sinks the ones that are.

**The honest caveat, stated once and not repeated per row:** Family B needs item
*volume*. Manual itemising is rare, and ingest cannot produce an itemised
candidate at all yet — `pending_line_item` does not exist (§16 Q7). The schema
is ready; the data is not, and it arrives with OCR. Nothing in Family B is
promised before P4.

---

## 2. Constraints that bind every surface here

These are not aspirations. Each is already enforced somewhere, and a chart that
violates one is a build failure rather than a review comment.

| Constraint | Source | What it means for a chart |
|---|---|---|
| Pre-binned data only | §11 | A chart never receives more points than it has horizontal pixels. A zoom is a **re-query**, not a transform over held data (ADR-0005). |
| Analytics reads `daily_rollup` | `CLAUDE.md` §8 | Never `ledger_entry`. Drill-downs read base tables via Paging 3. Family B is the one carve-out, and §5 explains it. |
| No netted figure | Law 2 | No chart, total, axis or legend may combine the two books. Every rollup statement binds `ledger`; `LedgerIsolationTest` fails the build otherwise. |
| Money is `Long` minor units | Law 3 | `sum_minor` is `Long`. Chart *coordinates*, σ/μ ratios and index values are legitimately real-valued; a money amount never is. |
| Hand-rolled `Lf*` Canvas | ADR-0005 | No charting dependency. Primitives live in `:core:designsystem`. |
| Goldens at 1× and 2× | `CLAUDE.md` §12 | Every chart gets Roborazzi goldens, **reviewed, never blind-recorded**. |
| Font scale 2.0 and RTL | `CLAUDE.md` §5 | Degrade by wrapping whole controls, never by clipping a label (BUG9). |
| Semantics per segment | §9.6 | A donut slice a screen reader can name. Charts are not decoration to be `clearAndSetSemantics`'d away. |
| Compact by default | `CLAUDE.md` §5 | **The chart is usually not the content.** In most of these surfaces the ranked list is what the user reads and the chart is orientation. Size them accordingly. |

---

## 3. The catalogue

Phase column: **P3** now, **P4** with OCR, **P5** polish/diagnostics, **P6+**
needs accumulated history.

### Family A — the conventional core (`SPEC.md` §5.6, §5.7)

| # | Surface | What the user sees | Data | Primitive | Phase |
|---|---|---|---|---|---|
| A1 | **Spend over time** | Stacked bar by category, or a line for the total, with a day/week/month bucket toggle | `daily_rollup` | `LfStackedBarChart`, `LfLineChart` | P3 |
| A2 | **Category breakdown** | Donut plus a ranked list with % and Δ against the previous period | `daily_rollup` | `LfDonutChart` + list | P3 |
| A3 | **Subcategory drill-down** | Nested list, tap-to-expand | `daily_rollup` | list + optional squarified treemap (§7.2) | P3 |
| A4 | **Merchant leaderboard** | Top-N plus "Other" | `daily_rollup` | `LfHorizontalBarChart` | P3 |
| A5 | **Payment method split** | Donut | `daily_rollup` | `LfDonutChart` | P3 |
| A6 | **Calendar heatmap** | Month grid, day-cell intensity | `daily_rollup` | `LfCalendarHeatmap` | P3 |
| A7 | **Budget progress** | Ring or linear per category, with burn-rate projection | `budget` + `daily_rollup` | `LfBudgetRing` | P3 |
| A8 | **Recurring detection** | Suspected recurring merchants: ≥3 occurrences, interval σ/μ < 0.25 | **base tables**, not rollups — interval clustering needs individual dates per merchant | list | P3 |
| A9 | **Window & filter machinery** | 8 time windows + custom; previous-period toggle on every one; 10 simultaneously-composable filters | `daily_rollup` + base tables | — | P3 |
| A10 | **Recurring cash-flow runway** | "₹18,400 of detected recurring charges fall before the 30th" | derived from A8 | list | P5 |

A8's note matters and is easy to miss: **recurring detection is the one Family A
surface that cannot read `daily_rollup`.** The rollup is a daily *sum* per
dimension; interval clustering needs the sequence of individual occurrence dates
for a merchant, which a sum has thrown away. It reads base tables via Paging,
which `CLAUDE.md` §8 already permits for drill-downs.

### Family B — item-grain intelligence *(the differentiator; all P4+)*

| # | Surface | What the user sees | Needs | Primitive | Phase |
|---|---|---|---|---|---|
| B1 | **Personal price index** | Per item, unit price over time — "Rice ₹60/kg in March, ₹72/kg now". Ranked "biggest movers" with inline sparklines, plus an aggregate basket index | `line_item.normalized_name` + `quantity_milli` + `unit_price_minor` | `LfSparkline` | P4 |
| B2 | **Price vs. quantity bridge** | "Groceries +₹1,240 vs last month = **+₹890 prices**, **+₹350 volume**, +₹0 mix" | same | `LfBridgeChart` | P4 |
| B3 | **Same item, different merchants** | "Milk — ₹58 here, ₹64 there" | items + `merchant_id` | `LfDotPlot` | P4 |
| B4 | **Pack-size unit economics** | "1 kg pack ₹58/kg · 5 kg pack ₹49/kg" | quantity + unit price | `LfDotPlot` (shared) | P4 |
| B5 | **Shrinkflation detector** | Same item, quantity fell, price held | item history ≥2 observations | list + `LfSparkline` | P6+ |
| B6 | **Basket composition drift** | What you actually buy at one merchant, over time | items + merchant + long history | stacked area (`LfStackedBarChart` variant) | P6+ |

**B2 is the one to build first of these.** It answers the question people
actually have — *"is it me, or is it prices?"* — and it is unanswerable without
quantity data. It is ordinary variance decomposition; the moat is the data, not
the arithmetic.

**B1 has a second argument that is not analytic.** A personal price index built
by anyone else means shipping a household's shopping basket to a server. Here it
is computed on-device, in an app with no `INTERNET` permission in release
(Law 6). That is a claim worth making explicitly in the UI.

### Family C — provenance and ingest analytics *(unique to an ingest-first app)*

| # | Surface | What the user sees | Data | Phase |
|---|---|---|---|---|
| C1 | **Capture coverage** | Share of spending, by value and by count, that arrived automatically vs. was typed by hand | `ledger_entry.source` | **P3 — shipped** |
| C2 | **Parser gap list** | Merchants you almost always enter manually — i.e. exactly where the ruleset is blind | `ledger_entry.source` + `merchant_id` | **P3 — shipped** |
| C3 | **Dedupe evidence** | "47 double-notifications suppressed this month" | `pending_transaction.suppressed_by_id` | P5 |
| C4 | **Confidence distribution** | Histogram of parser confidence; the low tail names the senders to fix | `pending_transaction.confidence` | P5 |
| C5 | **Pipeline latency** | Capture → pending → approved, and where the queue stalls | `pending_transaction` timestamps | P5 |

**C1 and C2 need no new schema and no OCR — they run on the vault as it exists
today,** which makes them the cheapest differentiated thing in the entire
catalogue and the reason they sit in P3 beside the specced views. C1 is built;
it reuses `LfHorizontalBarChart` and introduced no new primitive.

**Two decisions C1 made that the table above does not carry.**

*Three buckets, not two.* An **imported** entry is neither captured nor typed —
nobody entered it and no parser read it. Folding it into "automatic" would
inflate the one number this surface exists to report, and folding it into "by
hand" would understate it, so it gets its own bucket and is hidden when empty.

*It honours the analytics filters, including the source filter.* Filtering to
"Manual" does make the section read 100% typed by hand, which is useless but
true. The filter sheet promises "Narrow every figure on this screen", and one
section quietly exempting itself would make that copy a lie — while the useful
question, "how much of my *Food* spending is captured?", is the same mechanism.

*And the money is read at line grain*, like every other aggregate on the screen,
so C1's denominator is the total the user is already looking at. Summing
`e.amount_minor` is the obvious alternative and is wrong twice over: the
percentages would be against a figure appearing nowhere else, and the
`LEFT JOIN line_item` fans a split bill into one row per line, so an entry-grain
sum double-counts it outright.

**C2's three decisions, likewise.**

*A habit, not an accident.* A merchant needs at least three entries in the
window and at least two thirds of them typed before it counts as a gap. The
first threshold is `RecurringDetection.MINIMUM_OCCURRENCES`, deliberately: both
surfaces ask "is this a pattern", and answering it with two different numbers in
one app would be arbitrary. The second is what "almost always" means as a
number — a merchant captured half the time is *partly* covered, and the fix
there is a different job from writing a rule that does not exist.

*Ranked by frequency, not by amount.* Every row is a candidate parser rule, and
the value of writing one is the typing it saves in future — which is a
frequency. Money is the tiebreaker only when counts are equal. Ranking by amount
would put a once-a-year rent transfer above a chaiwala typed twelve times, and
the rent rule would pay back once.

*Unfiled entries are excluded.* A rule cannot target the absence of a payee, so
an "Unfiled" row would be the one line on the list nobody could act on.

**C2 buckets an *imported* entry as manual, where C1 counts it as neither** —
which looks like an inconsistency until you ask what each is for. C1 asks "did
this arrive by itself", and an import did not arrive at all. C2 asks "is the
ruleset blind to this merchant", and an import is evidence that no rule read it.
Same column, two questions, pinned by a test so nobody quietly unifies them.

**Unlike A8, C2 uses the selected window rather than a fixed lookback.**
Recurring detection reaches past the range because it needs a *sequence of
dates* to fit an interval, and a one-month view would find nothing. C2 needs
only counts, and a count over the range the user is looking at is a denominator
they can see.

They also close a loop nothing else in the category has: C2 tells the user where
capture is blind, and each blind spot is a candidate for a parser rule and a
permanent corpus fixture (`CLAUDE.md` §11 — the corpus only grows). The chart
improves the product that draws the chart.

C3–C5 are diagnostic rather than financial. They belong on the diagnostics
screen §11 already schedules for P5, not on the Analytics tab — see §7.

### Family D — identity

| # | Surface | What the user sees | Phase |
|---|---|---|---|
| D1 | **Two-book parallel view** | Debit and credit on mirrored axes, visibly separate, **never a net line** | P3 |

Low analytic value and high identity value. Law 2 forbids the netted figure
every competitor's home screen is built around, so a treatment that makes the
separation legible turns a constraint into the product's signature. Cheap: a
variant of `LfStackedBarChart` with a reflected y-axis and per-book colouring
already required by §5.5.

---

## 4. Phasing summary

| Phase | Surfaces | Gate |
|---|---|---|
| **P3 — Analytics** | A1–A9, C1, C2, D1 | 5Y query < 300 ms, measured on device. Goldens for every new chart, reviewed. |
| **P4 — OCR** | B1–B4 | Item extraction recall ≥ 90% (§13) is the real gate; the price surfaces are worthless on a corpus that misreads quantities. |
| **P5 — Polish** | A10, C3–C5 | Diagnostics screen ships; all §11 budgets met. |
| **P6+** | B5, B6 | Needs months of accumulated item history. Not scheduled. |

P3 is 12 surfaces, of which 9 are specced and 3 (C1, C2, D1) are new and cheap.
**No new schema is required for any P3 surface** — v9's `daily_rollup` and
`budget`, plus columns that already exist, cover all of them.

---

## 5. Data structures

**Family A and D:** `daily_rollup` (schema v9, shipped), except A8/A10 which
read base tables via Paging 3.

**Family C:** no new schema at all. `ledger_entry.source`, `merchant_id`, and
the `pending_transaction` columns already exist.

**Family B needs one new structure, and the recommendation is a view, not a
table.** An *item observation* is `(normalized_name, local_date, merchant_id,
quantity_milli, unit_price_minor, entry_id)` — which is exactly `line_item`
joined to `ledger_entry`. So:

- **Start with a `@DatabaseView`.** No writer, no rollup, no reconciliation, and
  it cannot drift from the base tables because it *is* the base tables. Given
  ADR-0006 exists specifically because a materialised table can go stale, not
  materialising this one is the cheaper correct answer until measurement says
  otherwise.
- **It binds `ledger = 'DEBIT'`,** like the two entry views (ADR-0002). A unit
  price is a spending concept, and `LedgerIsolationTest`'s rule must extend to
  it on the day it appears.
- **Materialise only on evidence.** Item rows are far fewer than 5 years of
  daily rollup rows, so the view is likely fast enough. If a measurement at P4
  says otherwise, that is the trigger for an ADR — not a reflex.

**`daily_rollup` must not grow an item dimension.** It sums money per dimension.
A unit price is a *ratio*, it is not additive, and putting it in a `SUM` table
is a category error that also parks a non-money real number next to a Law 3
column. This is written down here because "just add it to the rollup" is the
obvious wrong move and v9 deliberately leaves room to do it properly.

---

## 6. Primitive inventory (`:core:designsystem`, ADR-0005)

| Primitive | Serves | Notes |
|---|---|---|
| `LfLineChart` | A1 | Shares the axis engine below |
| `LfStackedBarChart` | A1, D1, B6 | The hard one: ticks, label collision, pan/zoom. The gesture is *reported* as an `LfViewportGesture`, never applied — the ViewModel moves the window and re-queries |
| `LfDonutChart` | A2, A5 | `drawArc` in a loop |
| `LfHorizontalBarChart` | A4, C1, C4 | Barely a chart — a row with a weight-proportional fill |
| `LfCalendarHeatmap` | A6 | A `LazyVerticalGrid` |
| `LfBudgetRing` | A7 | `drawArc` |
| `LfSparkline` | B1, B5 | Tiny; inline in a list row |
| `LfBridgeChart` | B2 | P4 |
| `LfDotPlot` | B3, B4 | P4 |
| `LfTreemap` | A3 | Squarified layout in `LfTreemapLayout`, returning fractions so the geometry is unit-tested off-device (§7.2) |
| **Axis engine** | A1, D1, B2 | Tick selection **unit-tested independently of rendering** (`AxisTicksTest`) — it is the one piece here with a correct answer that does not depend on how it looks |

Seven primitives cover all of P3. Three more arrive at P4. Note how many surfaces
are *lists with a small graphic*, not charts: that is deliberate and matches
`CLAUDE.md`'s compactness brief.

---

## 7. Open decisions

**7.1 — Categorical colour. Owner's call, and it blocks A2/A5 rendering.**
A donut over a dozen categories needs a dozen distinguishable colours.
`LfTheme` has no categorical ramp, and `CLAUDE.md` is firm that the palette does
not change to solve a layout problem. But `category.color_argb` already exists —
every category carries the colour the user chose. Options:

1. Use `color_argb` as-is. No new palette, perfectly faithful, but those colours
   were picked to read as small dots in a list; two adjacent arcs can come out
   near-identical, and contrast against the card is not guaranteed in either
   theme.
2. Use `color_argb` as the hue but normalise lightness/saturation per slice so
   arcs stay separable and contrast holds. *Recommended starting point.*
3. Add a proper categorical ramp to `LfTheme` and keep `color_argb` for list
   dots only.

**7.2 — Treemap (A3). Settled: built in P3, as a toggle beside the list.**
This section previously deferred it to P5 behind a trigger; the owner asked for
it during P3 and it shipped there, so the plan says so rather than describing a
state the code left behind.

Squarified layout (Bruls, Huizing & van Wijk) is the one non-obvious algorithm
in the catalogue, and it was named here as the first credible reason to reopen
ADR-0005. **It did not become one:** the layout is ninety lines of arithmetic in
`LfTreemapLayout`, it returns fractions rather than pixels, and it is therefore
unit-tested off-device — areas proportional, no overlaps, nothing outside the
frame, tiles roughly square rather than slivers. A charting dependency would
have brought a second rendering system to avoid writing it.

**The list stays primary.** The toggle swaps the graphic and leaves the ranked
list and its drill-down untouched, which is `CLAUDE.md`'s rule that the graphic
orients and the list is the content. Tile labels are drawn only where they
measurably fit; the ones that do not are simply absent, because text spilling
across neighbours would make the *large* tiles unreadable in order to label a
small one — BUG9's rule as it applies to a Canvas.

**7.3 — Where Family C lives.** C1 and C2 are user-facing and belong on
Analytics. C3–C5 are diagnostics and belong on the P5 diagnostics screen. The
open question is whether C1 deserves a home-screen slot; it is arguably the most
*novel* thing the app can show, and burying it on a tab wastes it.

**7.4 — Materialise item observations?** See §5. Decide at P4, on a measurement.

---

## 8. What is deliberately not here

- **Net worth, cashflow netting, or any single combined figure.** Law 2. This is
  not an omission to be fixed later.
- **Anything requiring a network call** — live prices, benchmark comparisons
  against other users, market data. Law 6, and no `INTERNET` in release.
- **Cross-user or aggregate-population comparisons** ("you spend more than 60%
  of people like you"). Impossible on-device by construction, and the privacy
  position in §1 is worth more than the feature.
- **FX-converted analytics.** `amount_minor` is always base currency (§5.8);
  there is nothing to mix.
- **Substitution detection** ("you switched from X to Y"). Plausible on item
  data but fuzzy, and it would need a similarity model to be more than a guess.
  Not scheduled; recorded so it is not re-invented as though it were free.
