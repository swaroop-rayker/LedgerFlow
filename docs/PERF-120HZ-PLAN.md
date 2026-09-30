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

## 6. Startup

1. `reportFullyDrawn()` once the vault is open and the first screen has data,
   so "time to full display" measures Home rather than the unlock frame.
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
