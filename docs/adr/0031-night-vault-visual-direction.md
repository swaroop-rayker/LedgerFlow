# ADR-0031 — Night Vault: the visual direction, with shapes from the reference samples

- **Status:** Accepted
- **Date:** 2026-10-28 (decided 2026-10-25; recorded at design-system step F1)
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** Supersedes `SPEC.md` §9.1's palette ("Toshl-adjacent", indigo accent) and §9.2's single-family type rule. ADR-0005 (hand-rolled charts) stands and governs the chart style below.
- **Spec sections touched:** `SPEC.md` §9.1, §9.2, §9.4, §10; `docs/V2-PLAN.md` §8; `DESIGN_SYSTEM_PLAN.md` §0 (Q4, Q6–Q9), §4.2, §4.5

## Context

The design-system work on `redesign/design-system` (`DESIGN_SYSTEM_PLAN.md`)
put every colour, type style, space, shape and size behind a role
(`LfTheme.colors`, `.type`, `.space`, `.shapes`, `LfLayout`) in steps A–E,
at **today's values**, so a direction could be applied by changing primitives
rather than screens. Phase 2 drew three directions, each as a Design canvas
with Home in both themes, Review, Add entry, the loading, empty and error
states and a style sheet:

- **A · Two Books:** a paper-ledger look built from the app's logo.
- **B · Night Vault:** dark and private.
- **C · Steady:** today's colours with the inconsistencies fixed.

The owner then supplied reference samples, `D:\LF_UI_UX_design_sample`
(outside the repo; `cards.webp` is a component sheet, `pfp.png` a Profile
screen, the rest screenshots of settings, security, sheets, sign-in, PIN and
onboarding screens), with the brief: take ideas from them; take navigation,
buttons, controls and forms from `cards.webp`; ignore its iOS kit; charts in
the same spirit but not copies.

Two forces constrain any choice. **Colour carries meaning in this app**:
money out, money in and attention are three different facts, and Law 2 keeps
the two books apart in every figure, so a palette that blurs them, or a
decorative gradient that reuses their hues, misinforms. And **the compactness
brief** (`CLAUDE.md` §5) is a standing constraint: rows grouped in panels with
hairlines, small type, chrome trimmed toward the small end of the scale.

## Options considered

### Option A — Two Books

Distinctive and on-brand, but a paper look is a light-first design, and the
app's primary use is at night (§9.1 has always made dark the default). Its
warm paper ground also made the money-in green and the attention amber harder
to tell apart. Not chosen.

### Option B — Night Vault

A graphite ground, bone-white text and a bone primary action, coral and mint
kept for money only, amber for attention, and pink for problems so an error
can never be read as an amount. IBM Plex Mono for every number and date,
Inter for words. Grouped panels, and a one-line status at the top of Home.
**Chosen (owner, 2026-10-25).**

### Option C — Steady

The cheapest: no palette decision, only consistency. But it kept the indigo
accent beside coral and mint, three saturated hues competing on one row, and
the error colour stayed money-out's coral, which is the confusion this work
set out to remove. Not chosen.

### On the samples: their look, or only their parts

The samples' own palette is violet with lavender tiles. Taking it would have
replaced Night Vault's colours with a sixth hue carrying no meaning. **The
owner kept Night Vault's colours and fonts and took the samples' shapes,
components and layouts** (2026-10-25).

## Decision

**Night Vault's colours and fonts; the reference samples' shapes, sizes,
components and layouts.** The canvas is the visual record:
https://claude.ai/artifact/BHYXqJ93MQQKV9CJErQJ7N ("Style sheet" for the
values; the third to fifth rows for the sample-derived parts).

### Colours

| Role | Dark (default) | Light | Use |
|---|---|---|---|
| `surface.canvas` | `#121316` | `#F4F3F0` | the page |
| `surface.raised` | `#1A1B1F` | `#FFFFFF` | panels, cards, sheets |
| `surface.overlay` | `#222429` | `#EEECE8` | dialogs, the tonal button, icon buttons |
| `text.primary` | `#ECE8E0` (bone) | `#17181B` | |
| `text.secondary` | `#A39F96` | `#55534D` | |
| `border.subtle` | `#2B2D33` | `#E3E0D9` | hairlines inside and around panels |
| `border.strong` | `#75716A` | `#837F77` | control edges; a switch's off thumb (see below) |
| `action.primary` | bone `#ECE8E0`, graphite text | `#17181B`, white text | the filled button, switches, checks, selection |
| `money.outgoing` | `#FF8A80` | `#B3362F` | money out, and nothing else |
| `money.incoming` | `#6FD6A8` | `#1D7350` | money in, and nothing else |
| `status.warning` | `#F2B457` | `#8F5B00` | attention: budgets, reminders |
| `status.danger` | `#F49AC1` (pink) | `#A8336F` (magenta) | errors and destructive actions; **never** money-out's coral |

Five values are not on the canvas and were chosen at F2 inside the existing
contrast gate (§9.6, `ContrastTest`, E1's `NoticeContrastTest`,
`Bug63_OffSwitchIsVisibleTest`) rather than by eye, with the owner's approval
on 2026-10-28: the light overlay; `text.muted`, `#908C84` / `#6A675F`; the
light danger; and `border.strong` in both themes. The canvas draws control
edges at `#3A3D44`, about 1.6:1 against a panel, and the same role draws an
off switch's thumb, which BUG63 requires at 3:1; the values above are the
lightest that keep it. A tinted fill (`*Subtle`, notices, icon tiles) is the
role's colour over the surface at `SUBTLE_TINT`, as today.

**Category colours** keep the curated 16-swatch palette and its per-theme
contrast guarantee against both surfaces; only their tiles change shape (F3).

**No dynamic colour (Material You).** `LfTheme` has never offered it, and §9.1's
"opt-in toggle" was never built: with colour carrying meaning, a wallpaper must
not be able to repaint money out as green. The theme setting (V2-3) chooses
System, Light or Dark; it never changes the palette.

### Type

- **Inter** for names, sentences, labels and controls: unchanged
  (`InterVariable.ttf`, six registered weights for the bold-text setting,
  BUG61's direction-aware alignment).
- **IBM Plex Mono** for every amount and date, and for the status line.
  Monospaced, so figures align without relying on `tnum`. A second bundled file
  under the SIL Open Font License 1.1: no download (Law 6), the licence in
  `assets/licenses/` beside Inter's, the size counted against §11's APK budget,
  and the weights cut in XML as Inter's are. It lands in F2 with its measured
  cost.
- `mnemonic` stays Inter with `ss02`. Changing the font a recovery word is
  copied from is its own decision, with its own transcription check, not a
  side effect of this one.

### Shape and size (from the samples)

| Role | Value |
|---|---|
| `container` | 16 dp (sheets 24 dp on their top corners) |
| `row` | 0 inside a panel; 16 dp standalone (applied in D3) |
| `control` | 16 dp; 12 dp on the 40 dp icon tile |
| `dialog` | 24 dp |
| `full` | 50 %: pills, chips, segmented tabs, the +, avatars, progress bars |

Buttons 56 dp (large) and 40 dp (small); fields 56 dp; icon tile 40 dp;
avatar 64 dp with a 2 dp ring; row minimum 56 dp; bottom bar 64 dp with the +
56 dp, raised half above it, and a label under every icon. 48 dp stays the
touch-target floor.

### Components and layouts (from the samples)

Navigation, select and switch rows; option, confirm and filter sheets (built
in E1); notices and the progress bar (E1); tinted icon tiles on entry rows;
pill segmented tabs; the filter bar (period pill and filter icon); the raised-+
bottom bar; a top bar with Back, a centred title and an overflow (F5). Profile
replaces the Settings tab (D4, `docs/V2-PLAN.md` D7 as amended).

### Not taken from the samples

The violet palette; the iOS kit; one card per transaction row (rows stay
grouped in one panel, as `pfp.png` itself does); multi-hue gradient charts;
3D illustrations (no artwork exists, `DESIGN_SYSTEM_PLAN.md` Q9).

### Charts

Hand-rolled, no dependency (ADR-0005), one book per chart (Law 2). A new
`LfAreaChart` for single-series trends: a monotone cubic curve that never
leaves the data's range, a fill fading from 24 % to 0 %, the latest or selected
point marked with a Plex Mono value pill. The donut gets rounded caps, 2 dp
gaps and a centre total in category colours, never a gradient; bars get
rounded tops on a quiet track; progress bars are 8 dp and fully rounded (F6).

## Consequences

- **Every golden changes once, in F2** (colours and type), then again only
  where F3 changes a component. Each is reviewed, never re-recorded blind.
- **Errors stop looking like money.** Through Phase E, screens moved their
  failures to `status.danger` while it still pointed at money-out's coral; F2
  re-points it to pink and those screens change with no edit of their own.
- **A second font file** in the APK, with its licence and a measured cost.
- **Contrast is re-proved, not assumed:** every text-on-surface pair, every
  notice tone and every progress fill on its track, in both themes, by the
  existing tests.
- **The canvas and the reference folder are design records**, not build
  inputs. Nothing in the build reads either; a value reaches the app only by
  being written into a primitive here.

## What would make us revisit this

- A contrast pair the gate fails that no value within these hues fixes.
- Plex Mono's measured APK cost breaking §11's budget.
- A user study showing coral and pink confused under colour-vision
  deficiencies at the sizes the app draws them.

## Verification

- F2: `LfContrastTest` and `NoticeContrastTest` green in both themes; every
  golden reviewed pair by pair; the device in both themes.
- F3: the stability report, Ledger and Inbox benchmarks (cool, interleaved),
  and row heights before and after from `uiautomator dump`.
- The canvas's "Style sheet" artboard and this ADR agree on every value they
  both name.
