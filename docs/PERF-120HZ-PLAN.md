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
   **Done 2026-10-16** with Home v1 (`ReportDrawnWhen` on Home's figures).
   First reading, LF Bench on SM-S721B (Android 17, thermal status 0, cold
   launches after one warm-up): first frame 126-167 ms, fully drawn
   2.61-2.69 s. Not a regression (nothing measured full display before) but
   2.5 s of placeholders on the start screen. **Explained and fixed
   2026-10-16 (BUG56, ADR-0029).** Markers split it as: Keystore unwrap
   268 ms, then five SQLCipher key derivations of ~425 ms each, one after
   another (the guard's open, Room's writer, three readers), then 36 ms for
   everything Home reads. The derivation now runs once per launch. Fully
   drawn went from 2.65–2.75 s to **0.71–0.73 s** (interleaved, thermal
   status 0), with the first frame unchanged. What remains: Keystore ~0.27 s
   and one derivation ~0.4 s.
   **Split again 2026-10-18** (LF Bench at 2a9a197, 11 marked cold launches):
   ~45 ms to `openOnLaunch`; ~25 ms more before the Keystore unwrap started.
   That gap was not the wordlist warm-up, which takes 3-4 ms. The unlock
   resumed on the main thread after each `withContext(io)` while main drew
   the first frame. Then the Keystore unwrap, 262 ms: KEK-A is **StrongBox**
   (`KeyInfo.securityLevel` 2), and AES-GCM costs one ~60 ms secure-element
   round trip each for begin, AAD, update and finish. It is per-operation
   (a warm unwrap is also ~270 ms); a TEE key does the same in 14-33 ms.
   Then the one derivation, **~205 ms in release code**: the 645 ms quoted
   in ADR-0029 was a debug test APK. Then the open (~10 ms), Home's reads
   (~100 ms) and ~50 ms to the frame that reports.
   - `openOnLaunch` now runs on IO from its first line: the unwrap starts at
     ~48 ms, not ~75. Fully drawn median 725 → 698 ms (2 interleaved rounds
     of 4 after a warm-up, thermal status 0).
   - Measured and **not** worth it: overlapping the warm-up with the unwrap
     (no change), reading Home's currency and summary concurrently (they
     contend; no change), and a buffer-reusing `Pbkdf2` loop (no faster, and
     the function is pinned by golden vectors).
   - The two remaining large costs are both crypto. StrongBox could only go
     by weakening KEK-A to the TEE, which is not proposed. The derivation
     could only go by carrying the derived key in the KEK-A blob, which
     needs an ADR superseding ADR-0029. Neither is planned; both are the
     owner's call.
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
