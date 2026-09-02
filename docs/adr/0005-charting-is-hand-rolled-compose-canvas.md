# ADR-0005 — Charting is a hand-rolled Compose `Canvas` layer, not a library

- **Status:** Accepted
- **Date:** 2026-09-02
- **Deciders:** owner
- **Supersedes / Superseded by:** none. Closes the "decide in ADR-005" note in `SPEC.md` §10.
- **Spec sections touched:** `SPEC.md` §5.6, §9.4, §10, §11; `CLAUDE.md` §9, §10, Law 6

## Context

P3 ships seven chart surfaces (`SPEC.md` §5.6):

| Surface | Shape | Genuinely hard part |
|---|---|---|
| Spend over time | stacked bar + line, period toggle | axis ticks, label collision, pan/zoom |
| Category breakdown | donut + ranked list | arc sweep, centre label |
| Payment method split | donut | — same primitive |
| Merchant leaderboard | horizontal bar, Top-N + "Other" | none; it is a `LazyColumn` of rows |
| Calendar heatmap | day-cell intensity grid | none; it is a fixed 7-column grid |
| Budget progress | ring / linear progress | none; Material already ships both |
| Subcategory drill-down | nested list, **treemap optional** | squarified treemap layout |

`SPEC.md` §10 named "Vico or a hand-rolled Compose `Canvas` chart layer" and deferred the choice here. `CLAUDE.md` §9 already rules out MPAndroidChart and every other View-based chart, so the field is Compose-native libraries versus writing it.

Three constraints make this a decision rather than a default:

1. **This is a shipped dependency, not a test-only one.** Roborazzi cost the release APK nothing. A chart library is dex in every install, against a §11 budget of 15 MB that is *already* provisional and already spoken for by SQLCipher, a bundled Inter (859 KB), and — the real unknown — bundled ML Kit at P4.
2. **§11 forbids handing a chart more points than it has horizontal pixels.** Data arrives pre-binned from `daily_rollup`. This deletes the single feature a chart library exists to provide, and it does worse than delete it — see the Decision.
3. **`CLAUDE.md` §5 forbids a hardcoded colour, dimension or type size anywhere.** Every visual value must come from `LfTheme`. A chart library's axis strokes, gridline colours, label typography, legend spacing and empty-state are its own defaults by construction, and each one is a value we must either thread a token through or accept off-palette.

### A measurement, taken before arguing about headroom

The APK budget is not currently in a state where "will this library fit?" is answerable:

```
app-smsFull-debug.apk    22.77 MB   (universal: 4 ABIs)
app-playSafe-debug.apk   22.77 MB
  libsqlcipher.so  x86 2.24 · x86_64 2.23 · arm64-v8a 2.10 · armeabi-v7a 1.05 MB
  classes*.dex     ~35 MB uncompressed, unshrunk
```

§11's budget says **arm64 split, release**. What exists on disk is a **universal debug** APK — four ABIs, no R8, no resource shrinking. The two numbers are not comparable and nobody has ever produced the one §11 specifies. **This also means `ci.yml`'s APK-size step fails on the first CI run** (it globs `*/outputs/apk/*/debug/*.apk` and compares 22.77 MB against 15 MB) — a pre-existing defect, unrelated to charting, recorded here because it is the reason this ADR cannot quote real headroom. See Consequences.

So the honest position is: **the release APK budget is unmeasured, and P3 should not be the phase that spends an unknown quantity of it.**

## Options considered

### Option A — Vico (`com.patrykandpatrick.vico`)

| | |
|---|---|
| Summary | Compose-native, Canvas-based, actively maintained, the library `SPEC.md` §10 named. Cartesian charts (line, column, stacked column) are its core competence. |
| Cost | Release dex in every install, against an unmeasured budget. Its Compose-runtime coupling becomes a gate on this project's Compose upgrades. |
| Risk | **It covers three of the seven surfaces.** Vico is a *Cartesian* chart library: it does the stacked bar and the line well. Donut, calendar heatmap, treemap and the budget ring are not Cartesian and are not in it — they still get hand-rolled. So the dependency does not remove the Canvas layer, it adds a second rendering system beside it, and `CLAUDE.md`'s "one shape per screen" becomes "two chart engines with different label metrics on the same tab". |

### Option B — Hand-rolled Compose `Canvas` primitives in `:core:designsystem`

| | |
|---|---|
| Summary | `LfStackedBarChart`, `LfLineChart`, `LfDonutChart`, `LfHorizontalBarChart`, `LfCalendarHeatmap`, `LfBudgetRing` — `DrawScope` plus `TextMeasurer`, no new coordinate. |
| Cost | We write and own axis-tick selection, label-collision avoidance, hit-testing for selection, and the pan/zoom gesture. Realistically the largest single item in P3's UI work. Four of the six are close to trivial (heatmap is a `LazyVerticalGrid`, horizontal bar is a `Row` with a weight, the ring is `drawArc`, the donut is `drawArc` in a loop); the time chart is the one with real content. |
| Risk | A hand-written axis is where naive implementations look wrong — ticks at 0/117/234, labels overlapping at font scale 2.0. Mitigated by the gate that already exists (below), not by care. |

### Option C — a smaller/newer Compose chart library

Rejected in one line, because it is the worst of both: the same release-dex cost and the same partial coverage as Option A, plus a maintenance profile this project cannot carry — a single-maintainer chart library that stops tracking Compose becomes a blocker on the whole app's toolchain, and there is no upstream to escalate to.

## Decision

**Hand-roll the chart layer as Compose `Canvas` primitives in `:core:designsystem`, prefixed `Lf`. No charting dependency is added.**

The argument that decides it is not APK size, and not maintenance. It is that **§11's pre-binning rule inverts the library's value proposition.**

A chart library earns its keep by owning the data→pixels transform: it holds the series, it owns the viewport, and pan/zoom is a transform it applies internally. §11 forbids that arrangement here — the chart may never hold 1,825 daily points, so a zoom gesture is not a transform over held data, it is a **signal to re-bin and re-query `daily_rollup` at the new resolution**. That query is ours, the binning is ours, and the viewport state has to live in the ViewModel where the query is issued. Adopting a library therefore means reaching into its gesture handling to intercept the zoom, suppressing its internal transform, and feeding it a new dataset each time — fighting the exact subsystem we are paying dex for. The remaining value is drawing rectangles and text, which is `drawRect` and `TextMeasurer`.

The second argument is coverage. Option A leaves four of seven surfaces hand-rolled anyway. A decision that adds a dependency *and* keeps the work is not a trade, it is an addition.

**This is not a close call for the four simple surfaces and it is close for the time chart.** If the stacked-bar axis proves to be a tar pit, the cheap reversal is to adopt Vico for that one surface only — the module boundary (`:core:designsystem`, `Lf`-prefixed composables with our own parameter types) is deliberately shaped so a library could be swapped in behind `LfStackedBarChart` without any caller changing. Reversal is one file, not a phase.

**Placement:** `:core:designsystem`, not `:feature:analytics`. Charts are design-system components under §9.4, they take `LfTheme` tokens, and — decisively — the Roborazzi harness already lives there and already records every golden at **1x and 2x font scale**. A chart written in that module inherits `CLAUDE.md` §12's "renders at fontScale 2.0" gate mechanically instead of by inspection. `:core:domain` is not a candidate under any option (ADR-0014), and the kickoff's note that a chart library "belongs in `:feature:*`" is moot once no library is taken.

## Consequences

**What this makes easy.** Every chart takes `LfTheme` colours and `LfSpacing` by construction, so the palette rule and "one shape per screen" hold without policing. Semantics for §9.6 accessibility can be attached per segment (`contentDescription` on each donut slice) rather than fought for through a library's node tree. Screenshot goldens are deterministic because nothing animates unless we animate it — a library that animates on first composition is a known source of flaky Roborazzi diffs, and we simply do not have one. Zero release-APK cost, which matters most precisely because the budget is unmeasured.

**What this makes hard.** Axis-tick selection and label collision are ours, at every font scale and in RTL. Hit-testing for "tap a bar to drill down" is ours. Pan/zoom inertia is ours. None of this is research, all of it is work, and the estimate for the time chart should be treated as the least reliable number in P3.

**What we now have to maintain forever.** Six `Lf*` chart composables, their goldens at 1x and 2x (§12 requires the goldens be *reviewed*, never blind-recorded), and a tick-selection routine with its own unit tests — the one piece of this with a correct answer independent of how it looks.

**Two things this ADR does not fix, and must not be read as fixing.**

1. **`ci.yml`'s APK-size step measures the wrong artifact** (universal debug, all four ABIs) against §11's arm64-release budget, and would fail at 22.77 MB on the first CI run for a reason that says nothing about the app. That is a CI defect predating P3, it belongs with `SPEC.md` §16 Q10's "confirm the APK budget at P4", and it is out of this ADR's scope. It is recorded here because it is *why* no headroom figure appears above.
2. The **real** arm64-release figure is still unknown. This decision spends none of it, which is the only responsible position while that is true.

**What would make us revisit this.** Concretely: (a) the time-chart axis or pan/zoom exceeds roughly a week of work, in which case adopt Vico behind `LfStackedBarChart` alone; (b) `SPEC.md` §5.6's optional treemap is promoted to required — squarified treemap layout is the one shape here with a non-obvious algorithm and the first credible reason to take a dependency; (c) a measured arm64-release APK shows headroom *and* a future phase wants interactive charts far beyond §5.6.

## Verification

- `verifyRoborazziSmsFullDebug` covers every chart at 1x and 2x font scale; `preMergeCheck` runs it. A chart that clips a label at 2x fails the build (BUG9's gate, applied to a new surface).
- A `libs.versions.toml` diff adding a charting coordinate is the visible signal that this ADR is being reopened; there is no separate guard, because adding a shipped dependency already requires `CLAUDE.md` §10 sign-off.
- Tick selection is unit-tested independently of rendering (`AxisTicksTest`): given a range and a pixel width, the chosen ticks are round numbers and do not overlap at the measured label width.
