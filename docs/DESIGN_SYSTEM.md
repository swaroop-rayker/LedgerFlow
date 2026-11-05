# LedgerFlow design system

How to build a screen that looks and behaves like the rest of the app. This
is the working guide; the reasons behind each choice are in
`DESIGN_SYSTEM_PLAN.md` (the redesign, steps A–G), `docs/adr/0031` (Night
Vault) and `SPEC.md` §9 (the UI specification). `CLAUDE.md` §5 holds the
rules that fail a review.

Everything here lives in `:core:designsystem`, except the composables that
know about ledger data (`LfEntryRow`, the phrase entry, the line-item editor),
which live in `:core:ui`.

---

## 1. The three layers

| Layer | Where | Who reads it |
|---|---|---|
| **Primitives**: raw values (`LfPalette`, `LfScale`, `LfTypeScale`) | `theme/primitive/` | the theme and component tokens only |
| **Roles**: values by job (`LfTheme.colors`, `.type`, `.space`, `.borders`, `.shapes`; `LfLayout`) | `theme/` | screens and components |
| **Component tokens**: what one component draws with (`LfButtonTokens`, `LfChartTokens`, …) | `component/LfComponentTokens.kt`, `chart/LfChartTokens.kt` | that component only (`internal`) |

A screen reads **roles**, never a primitive and never a raw `dp`, `sp` or
`Color`. `designTokenCheck` (part of `preMergeCheck`) fails the build on a
hard-coded value outside the design system; its allowlist is empty.

The old names (`LfTheme.typography`, `LfTheme.spacing`, flat colours such as
`textPrimary` or `accent`) were removed at step G. Each had a role with the
same value.

---

## 2. Roles

### Colour (`LfTheme.colors`)

| Group | Roles | Use |
|---|---|---|
| `surface` | `canvas`, `raised`, `overlay` | the page; cards, panels, rows; dialogs, tonal buttons, pills |
| `text` | `primary`, `secondary`, `muted` | words, by importance |
| `border` | `subtle`, `strong` | hairlines; control edges (≥3:1) |
| `action` | `primary`, `onPrimary` | the filled button, switches, selection; budgets and goals |
| `money` | `outgoing`, `incoming` | **an amount, and nothing else** |
| `status` | `warning`, `danger`, `success`, `*Subtle` | attention; errors and destructive actions; good news |
| `chart` | `grid`, `track`, `axisLabel` | chart chrome |

Two rules people break:

- **Money colours are for money.** An error is `status.danger` (pink), never
  `money.outgoing` (coral), so a problem cannot be read as an amount. A
  budget is a plan, not money moving: `action.primary`, `status.warning` when
  over.
- **Colour is never the only cue.** A book's tag says "Expense" as well as
  being coral; a status dot sits beside words that say the same thing.

The values are in `SPEC.md` §9.1. Every text-on-surface pair is held to WCAG
AA by `LfContrastTest`. The app's theme is not the phone's: read colours from
`LfTheme.colors`, never from `isSystemInDarkTheme()`.

### Type (`LfTheme.type`)

`display` · `title` · `heading` · `body` · `bodySmall` · `label` ·
`caption` · `amountHero` · `amountRow` · `amountSmall` · `figureLabel` ·
`mnemonic` · `mnemonicIndex`.

- **Words in Inter, figures in IBM Plex Mono.** Amounts (`amount*`), dates
  and times (`figureLabel`), and identifiers such as a package name are Plex.
  A line that is mostly words stays Inter even if it holds a time (the
  Inbox's stamp line: Plex cut "needs details" to "needs detai…").
- **A recovery word is `mnemonic`**, Inter with its disambiguation set; only
  its number is Plex (`mnemonicIndex`).
- Every style reads in its text's direction and aligns to the screen's
  leading edge (BUG61). To align explicitly use `LfTextAlign.start`/`end`.

### Space (`LfTheme.space`)

| Role | Value | For |
|---|---|---|
| `screenEdge` | 24 | the page's side margin |
| `blockGap` | 24 | between blocks of a one-task step |
| `sectionGap` | 16 | between a page's sections |
| `cardInset` | 16 | inside a card |
| `rowInset` | 16 | a row's leading and trailing inset |
| `itemGap` | 8 | between items in a section |
| `rowInsetVertical` | 8 | above and below a row's content |
| `inlineGap` | 4 | between pieces on one line |
| `listEnd` | 32 | after a list, to clear the bar |
| `actionBarInset` | 4 | a pinned bar's own padding |

Pick by **job**, not by size: two 16s that do different jobs are different
roles, so one can change without moving the other.

### Shapes (`LfTheme.shapes`) and borders (`LfTheme.borders`)

`container`, `row`, `control` 16 dp · `tile` 12 dp · `dialog` 24 dp ·
`full` (pills, the FAB, avatars). `borders.hairline` 1 dp for every
outline; `borders.emphasis` marks a state (an unsaved draft).

### Layout constants (`LfLayout`)

Sizes no style changes: `minTouchTarget` 48, `categoryTileSize` 40,
`categoryDotSize` 24, `panelRadius`, `dialogListMax` 320, `segmentMinWidth`,
`actionMinWidth`, `peekCardWidth`.

---

## 3. A screen is a template

Start every screen from a page template in `pattern/`, never from
`LfScaffold`:

| Template | For |
|---|---|
| `LfTabPage` | the four tabs (Home, Ledger, Analytics, Profile) |
| `LfDetailPage` | a list pushed over the tabs (Inbox, the bin, Budgets) |
| `LfSettingsPage` | a page of settings rows |
| `LfFormPage` | a form with a pinned action bar (Add entry, Review, Export) |
| `LfFlowStep` | one step of a flow (onboarding, Recovery, upgrades) |

The templates own the screen edge, the header, insets (once — BUG49), the
gap between items (`LfPageSpacing`) and the list's end. A page reached from
somewhere else takes `onBack`: the back arrow and the title on one line.

---

## 4. Components, by job

- **Rows and lists.** A list of things is **one panel with hairlines**, not a
  card per row: `LfPanel` + `LfDivider` for a short list, `lfGroupSlice` with
  `lfGroupPosition` when the rows are lazy items (the Ledger's days, the
  Inbox, Payment apps). `LfNavRow` opens a page (with an optional icon tile
  and value); `LfSwitchRow` toggles; `LfEntryRow` is a ledger entry.
- **Controls.** `LfButton` (filled for a screen's one primary action; inline
  for actions inside a row), `LfIconButton`, `LfChip` (with a Plex `count`),
  `LfSegmentedControl` (a pill row, or a card when it stacks — BUG79/80),
  `LfTextField`, `LfAmountField`, `LfAmountCard` (a capture form's head).
- **Text to read.** `LfProse` draws shipped text (What's new, Help, the
  policy) from `ProseMarkdown`'s small Markdown subset; `lfProseItems` for a
  page long enough to be lazy; the `Secondary` tone for an answer under its
  question. `LfExpandableRow` is that question: stateless, it says whether
  it is open.
- **Amounts.** `LfMaskedAmount` for a read-only figure that can be hidden
  (Hide amounts on Home); a figure inside a sentence uses `AmountMask` for the
  same dots. A block of hidden figures is the tap that shows them
  (`Modifier.lfRevealAmounts`), and the figure is never in the semantics tree
  while hidden.
- **Bars.** `LfFilterBar` (a period pill and a filter control),
  `LfLinkBanner` (one line that opens a screen), `LfBottomBar` + `LfFab`.
- **Overlays.** `LfDialog` (an irreversible step: `Warning`, naming the
  count), `LfOptionsSheet`, `LfConfirmSheet`, `LfFilterSheet`, the date
  pickers. Raw Material overlays are banned outside these.
- **States and feedback.** `LfStateContent` (loading, empty, error,
  unavailable), `LfNotice`, `LfErrorNotice`, `LfProgressBar`, and
  `LfFeedback`, the window's one snackbar.
- **Icons.** `LfIcons`, named by job. Lucide only, copied into `LfLucide.kt`
  from the source repository with its ISC licence; a new icon comes from the
  same place, never drawn by hand or from another family.

`SPEC.md` §9.4 is the full inventory.

---

## 5. Charts

Hand-rolled `Lf*` Canvas code, no charting dependency (ADR-0005), one book
per chart (Law 2), handed data already binned to the pixel width (SPEC §11).
`CLAUDE.md` §5's charts section has the rules; in practice:

- `LfAreaChart` for one series over time: a monotone curve that never passes
  its points (`LfMonotoneCurveTest`), a fading fill, a value pill.
- The donut, bars and progress bars are rounded at their free ends; a slice
  too small for an arc is a dot, never nothing.
- Measure text in `drawWithCache` keyed on equal inputs, and prove it with a
  redraw test (`LfStackedBarChartRedrawTest`, `LfAreaChartRedrawTest`).
- A pan or a pinch is reported (`LfViewportGesture`) for the view model to
  re-query; a chart never holds or transforms the series.

---

## 6. The rules that keep it consistent

- **A label never wraps or clips** (BUG9). Controls render one line; a row of
  controls is an `LfActionRow` that wraps whole controls; a control that can
  stack (the segmented control) decides from its labels' measured widths.
- **Compact by default** (`CLAUDE.md` §5): a row is about two lines; chrome is
  trimmed toward `sm`/`xs`; one container shape per screen.
- **Every change ships goldens** at 1x and 2x, light and dark, and RTL where
  direction matters, recorded and **reviewed against the previous ones**, never
  re-recorded blind. A screen nobody has a golden for is a screen nobody
  reviewed: Lock after's capsule (BUG79) went unseen until one was written.
- **Semantics.** A control announces what it does; a chart describes its data;
  a recovery word is `phraseSecret()`.
- **New component?** Tokens first (`LfComponentTokens.kt`), roles only, then
  the composable, then goldens and a test for the behaviour a golden cannot
  show.
