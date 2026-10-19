# Plan — 120 Hz hardening (P5 addendum)

Status: **proposed, not started.** Written 2026-09-30 after P5 step 1
(`c5200a6`, `68f38b6`, `6ae169f`). Nothing here is built until the owner says
so.

## 0. Where we stand (measured, SM-S721B, 2026-09-29)

| Condition | Ledger scroll at 120 Hz |
|---|---|
| In use — no tracing, the phone's own accessibility service (screen mirroring; clicks/focus only) | 0.2–0.3 % of frames late, original and final code alike. **Met.** |
| Under Macrobenchmark — tracing on, UiAutomation subscribed to every event (≈ a TalkBack user) | 12–14 late frames per five iterations (was 33). **Not met.** |

Every late frame had the same anatomy: a newly visible row composed (~1.4 ms),
its nodes created (~0.8 ms) and measured/laid out (~2 ms) inside the frame,
plus ~1 ms of touch dispatch. Heat is the largest single source of noise: a
warm phone (thermal status 2) erases the difference between old and new code.

The goal of this plan: **120 Hz met in the harsh condition too** — because
TalkBack users are real users, because cheaper phones have less headroom than
this one, and because v2 is for general users on devices we will never hold.

## 1. Measurement first (without it, every step below is a guess)

1. **Commit the A/B tooling** under `scripts/perf/`: the per-swipe framestats
   capture, its summariser, and the trace-processor queries used on
   2026-09-29 (late frames by jank type; main-thread self time; the worst
   frames' slices). Today they live in a session scratchpad.
2. **Thermal gate in the harness.** Before each iteration, read
   `dumpsys thermalservice`; wait (bounded) for status 0, and record the
   status and battery temperature in the results. A run that could not cool
   is marked, not averaged in.
3. **A late-frame metric, not only P99.** A `TraceMetric` that counts
   `actual_frame_timeline_slice` rows with `App Deadline Missed`, per
   iteration — the number that actually moved, and the one P99 hides behind
   one-off GPU compiles.
4. **Both accessibility conditions.** Keep the current (harsh) run, and add a
   run that drives the scroll with shell `input swipe` after dropping the
   UiAutomation connection (`FLAG_DONT_USE_ACCESSIBILITY`, API 33+) — to be
   verified; if Macrobenchmark cannot run that way, the scripted framestats A/B
   stays the "in use" measurement.
5. **More surfaces:** tab switches (Home ↔ Ledger ↔ Analytics ↔ More — the v2
   transition bug lives here), Inbox scroll, Analytics 5Y open and pan, Bin
   scroll, the entry form opening.
6. **A regression threshold** on the self-hosted runner: the late-frame count
   per surface may not rise by more than an agreed margin against the committed
   baseline. CI's Linux runners cannot run it (§11 of CLAUDE.md), so it is a
   pre-release step in `TESTING.md` until a self-hosted job exists.

## 2. Make a row cheap enough to build inside a frame

Target: a new Ledger row costs **≤ 1.5 ms** to compose + create + measure
(today ~4 ms under the harsh condition).

1. **Flatten `EntryRow`.** Today: Row → Row(semantics) → Box → Box → Text
   (swatch initial), Column → Text + `EntryRowBody` Layout → Text, Text, plus
   `LfIconButton` → Box → `IconButton` → `Icon`. One custom `Layout` with three
   text children and a drawn swatch removes ~8 layout nodes per row. The
   measured two-line/stacking behaviour (`EntryRowBody`'s BUG9 rules) is kept
   exactly — it becomes the one layout's measure policy.
2. **Draw the category dot**, don't compose it: circle + initial via
   `drawBehind` and one `TextMeasurer` shared by the list (its cache keyed on
   the letter), instead of a `Box` and a `Text` per row. Affects
   `LfCategoryDot` everywhere — goldens reviewed at 1× and 2×.
3. **A lighter icon button for lists**: one node with `clickable` (bounded
   ripple, 48 dp target, `Role.Button`) and `paint(painter)`, instead of
   `Box` + Material `IconButton` + `Icon`. Same look, same target size.
4. **Format off the main thread.** Build a row display model (title, stamp,
   amount, spoken description) in the Paging `map`, on the IO dispatcher, not
   in composition. `EntryRow` then only lays out strings.
5. **Drop the intrinsic measure** in `EntryRowBody` if (1) can decide
   side-by-side vs stacked from the already-measured widths.
6. Each step measured with §1's A/B, cool, interleaved — kept only if it moves
   the late-frame count.

## 3. Other lists and screens

Apply §2's rules to the Inbox, Bin, Categories/merchants lists and the
Analytics ranked lists; check the Analytics charts' draw cost (Canvas paths
rebuilt per frame during pan?) against §11's pre-binning rule.
The time chart's draw cost was its text, measured on every draw; fixed
2026-10-19 (§5). `LfMirroredBarChart` and `LfTreemap` still measure in draw.
The mirrored chart measures one string, which the measurer's cache holds.

## 4. GPU side

1. **Overdraw**: every row paints `surfaceRaised` plus a hairline border over
   the screen background. Measure with the GPU overdraw overlay (a Developer
   option the owner toggles — this plan does not change system settings), and
   collapse where a row's background repeats its parent's.
2. **First-use pipeline compiles** (15–67 ms once per install/update): note in
   `TESTING.md`; avoid introducing rare blend modes or per-frame
   `graphicsLayer` allocations (one 9.5 ms `flush layers` was seen).

## 5. Transitions (shared with v2 BUG-A)

Tab switches currently use Navigation Compose's default 700 ms cross-fade,
which draws two full screens every frame for most of a second. v2 replaces it
(see `docs/V2-PLAN.md`); this plan measures it before and after.

**Replaced 2026-10-19 (BUG57).** The surface is `TabSwitchBenchmark`, with
`LateFrameMetric` — §1.3's `App Deadline Missed` count, the first committed
piece of §1's tooling. It waits 1 s after each tap, longer than the old
transition, so the old and new builds run the same gesture.

First reading, LF Bench on SM-S721B, baseline profile required, 5 iterations
of 8 switches, interleaved old/new, thermal status 0 throughout but warming
(34.5 → 39.1 °C):

| Build | Start temp | Frames/iteration | Late frames, median (range) | frameOverrun P99 |
|---|---|---|---|---|
| old (700 ms cross-fade) | 34.5 °C | 676 | 5 (5–12) | 12.1 ms |
| new (fade-through) | 36.7 °C | 196 | 6 (3–10) | 20.2 ms |
| old | 38.0 °C | 669 | 7 (5–12) | 16.0 ms |
| new | 38.5 °C | 194 | 9 (5–10) | 28.2 ms |

**The fix removes ~70% of the frames a switch draws, not its late frames.**
Late frames are level within run-to-run spread (perhaps +1–2 per 8
switches). P99 rises because it is now a percentile of a third as many frames:
the few expensive ones — by assumption the incoming tab's first composition,
which both builds pay; not yet confirmed from the traces — weigh three times as
much. BUG57 was a legibility defect and is fixed; tab-switch *jank* is this
plan's §3 work (a cheaper first frame for Analytics and the Ledger), measured
against this table.

**Where the late frames are (2026-10-19, read from the "new" run's traces).**
Each frame is attributed to the last tap before it. Tabs are in the order
Ledger, Analytics, More, Home, twice. The worst main-thread frame per switch,
10 switches per tab:

| Tab | Right after the tap | When the transition ends (~200–300 ms) |
|---|---|---|
| Home | 28 ms (compose 9.6, measure 15.1) | 5 ms |
| Analytics | 25 ms (measure 10.4, draw 7.6) | **12 ms, twice per visit** (draw 7.7) |
| Ledger | 21 ms (measure 12.8: the visible rows are composed inside measure) | 5 ms |
| More | 12 ms | 5 ms |

Of the 40 late frames: ~19 were an incoming tab's first frame. ~13 were
Analytics redrawing after its transition. ~8 were 1–2 ms frames that started
late behind a big first frame.

The Analytics redraw: the tab's resume effect fires as the transition ends.
`load()` flips `isLoading` and then lands an equal snapshot, and each emission
gave `LfStackedBarChart` a new list and lambda. Every draw then measured every
tick and column label twice. A month of labels is more than the text measurer's
cache holds, so all of them were measured again.

**The first frame is usually classed `Dropped Frame`, not late.** The
ripple's RenderThread-only frames keep the display fed while the main thread is
busy, so a 20–37 ms frame often never counts in `framesLate`. `LateFrameMetric`
now also reports **`framesOverBudget`**: main-thread `Choreographer#doFrame`
slices longer than their own frame's budget. On identical code `framesLate`
ranged 23–40 per run, because which long frames get classed late is close to
random. Read both, and attribute from the traces before believing either.

**Fixed: the chart measures text once per data change** (`drawWithCache`,
keyed on equal inputs; `LfStackedBarChartRedrawTest` is red on the old chart).
Every golden was unchanged. Interleaved A-B-B-A, LF Bench, thermal status 0,
5 × 8 switches per run:

| Build | Start temp | Late frames per iteration (sum) | Over budget (sum) | Late frames, Analytics end phase |
|---|---|---|---|---|
| before | 35.7 °C | 6, 8, 7, 12, 6 (39) | 63 | 8 |
| after | 38.1 °C | 4, 7, 7, 7, 8 (33) | 48 | 3 |
| after | 35.8 °C | 3, 10, 7, 8, 10 (38) | 45 | 1 |
| before | 35.8 °C | 5, 8, 8, 9, 10 (40) | 59 | 9 |

Late frames are 79 → 71, over-budget frames 122 → 93. The end-phase frames
Analytics paid on every visit are gone: their draw went from 7–10 ms to 0.6 ms.
Its *first* draw still measures every label (~5 ms), and the other tabs' first
frames are untouched.

**Home as a `LazyColumn`: in by the owner's decision, not by the late-frame
rule.** Home's first frame went from 18–29 ms to 13–14 ms (median) in every
run, because the below-the-fold half of the page was no longer composed. But
it stays over 8.3 ms, so late frames did not drop (33, 38 and 23 before,
against 30 and 37 after). By this plan's rule it would not be in. The owner
took it for the shorter hitch: one missed vsync where there were two or three.
Every golden was unchanged. `DashboardScreenFirstFrameTest` is red on the old
`Column`.

**Skipped: More.** All eight of its rows fit on one screen, so a lazy list would
compose the same first frame.

**Still open:**
- Every tab's first frame (13–25 ms) is the remaining cost. For the Ledger that
  is §2 (a cheaper row).
- The others need per-composable timings that release traces do not carry.
- The ~2–3 ms disposal of the outgoing tab when the transition ends
  (`onForgotten`) shows up occasionally.

## 6. Startup

1. `reportFullyDrawn()` once the vault is open and the first screen has data,
   so "time to full display" measures Home rather than the unlock frame.
   **Done 2026-10-16** with Home v1 (`ReportDrawnWhen` on Home's figures).
   First reading, LF Bench on SM-S721B (Android 17, thermal status 0, cold
   launches after one warm-up): first frame 126-167 ms, fully drawn
   2.61-2.69 s. Not a regression (nothing measured full display before) but
   2.5 s of placeholders on the start screen; the split between vault open,
   the two recent-entry reads and the month snapshot needs a trace.
2. A startup profile (`includeInStartupProfile`) for dex layout, measured
   against the current baseline-only profile.

## 7. Order and exit criteria

§1 → §2 (Ledger) → §5 measurement → §3 → §4 → §6. Exit: in the harsh
condition, cool phone, **≤ 1 % of frames late on every measured surface**, and
no regression in the in-use condition; PSS stays under §11's 150 MB.

## Open questions for the owner

1. Is the harsh (TalkBack-like) condition the bar, or is "in use" enough?
2. Is a second, cheaper 90/120 Hz test phone possible? One flagship-class
   device cannot tell us what general users' phones will do.
