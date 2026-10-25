# LedgerFlow design system — Phase 1 plan

Branch `redesign/design-system`, cut from `origin/main` at `09b28ff` (2026-10-25)
and fast-forwarded to `0cc93ea` once V2-3 (PRs #8–#12) was merged.

**Status (2026-10-25):** plan approved; Q1–Q5 answered as recommended; Phase 2
done and the owner chose **B · Night Vault**
(canvas: https://claude.ai/artifact/BHYXqJ93MQQKV9CJErQJ7N). Phase 3 starts at
step A1.

**What this plan does:** checks the earlier audit against the code (§1), sorts
every hard-coded size into "keep" or "make it a token" (§2), maps what the UI
layer shares and what it repeats (§3), proposes the design system (§4) and the
screen-state system (§5), says how the rules get enforced automatically (§6),
and gives a step-by-step build order (§7). Every screen in `SCREENS.md` is
covered in §5.4.

### Words used here

| Term | Plain meaning |
|---|---|
| **Token** | A named design value ("the colour for money going out", "the gap at the edge of a screen") used instead of a raw number or hex code. Change the token, and every place that uses it changes together. |
| **Primitive token** | A raw value with a neutral name: `coral-500 = #E5645F`, `space-4 = 16dp`. Says *what* the value is, not *where* it's used. |
| **Semantic token** | A value named by its job: `money.out`, `surface.raised`, `text.muted`. Screens use these. |
| **Component token** | What one building block uses: "a card's corner radius", "a button's height". Points at semantic tokens. |
| **Component** | A reusable building block (button, card, row, dialog) in `:core:designsystem` or `:core:ui`. |
| **Template** | A reusable screen layout (header, content, pinned action bar) that knows nothing about which tab or route it lives under. |
| **Golden** | A saved reference screenshot. A test fails when the screen no longer matches it. |
| **Skeleton** | Grey placeholder shapes, in the layout's own shape, shown while data loads. |
| **Snackbar** | The short message strip at the bottom of the screen ("Moved to bin · Undo"). |

---

## 0. Decisions needed before Phase 3

These came up while checking the code. Each would change the plan, so they
need an answer first, not a guess.

| # | Question | Why it matters | Recommendation |
|---|---|---|---|
| Q1 | **The palette is locked in three places.** `SPEC.md` §9.1 fixes the colours; `docs/V2-PLAN.md` §3.2 says Appearance ships with "palette unchanged" and §8 lists "the palette" under what v2 does not change; `CLAUDE.md` §5 says "the palette does not change to solve a layout problem". | Any Phase 2 direction other than "keep the colours" overrides a written decision. | Treat it as an owner decision: one new ADR (next free number after the V2-3 branch's ADR-0030, so **ADR-0031**) plus `SPEC.md` §9 and `V2-PLAN.md` §8 updated in the same phase (Phase F1). Directions are still free to propose a new palette. |
| Q2 | **Resolved:** V2-3 merged as PRs #8–#12 (`9c9b523`…`0cc93ea`), and this branch was fast-forwarded onto it. Original note: **An unmerged V2-3 branch overlaps this work.** `claude/v2-3-app-lock` (worktree `v2-3-settings-pages-e3b2ac`, last commit 15 hours ago) changes 101 files: new screens (Lock, Appearance, Currency & region, Notifications, Payment-app allowlist, Security & privacy), `LfSwitchRow`, the `format/` package, `BudgetNotifications.kt`, and 8 existing screens (Analytics, Categories, Entry, Restore, Settings, Back up now, Diagnostics). It also adds a System/Light/Dark override and an animations setting. | Every implementation phase here touches those files. Running both at once guarantees merge conflicts, and the redesign would miss six new screens. | **Merge V2-3 into `main` before Phase 3 starts**, then rebase this branch. Phases A–C (§7) don't change pixels and could start earlier if needed, but they'd conflict in `LfTheme.kt` and the screen files. |
| Q3 | **Fixing the screen-state gaps means editing ViewModels and UI state classes.** Examples: a Retry event for the setup dead end, showing Budgets' messages, an Undo for Ledger deletes. | You asked not to change "business logic". These are screen state (UI layer), not the data layer or the money rules. | Allow changes to `*ViewModel.kt`, `*UiState`/`*State.kt` and screen files. Don't touch use cases, repositories, DAOs, `:core:domain`, `:core:data` or `:core:database`. Anything that needs those (editing a saved entry, undoing a budget delete) goes on an out-of-scope list (§5.5). |
| Q4 | **New font files.** A direction may need a second typeface. | It's a bundled file (Law 6: never downloaded) with an OFL licence that has to ship in `assets/licenses/`, per `LfFonts.kt`. | Approve per direction in Phase 2. No new *library* dependency is needed anywhere in this plan. |
| Q5 | **About twice as many goldens.** Adding dark theme doubles today's 135 goldens (after V2-3) to roughly 270, plus new ones for dialogs, sheets and states. | More Git LFS storage (memory: worktree goldens can be LFS pointers) and a longer CI `screenshot` job. | Accept. The alternative is dark mode staying unchecked, and dark is the default theme. |

---

## 1. The audit, checked against the code

Verdicts: **Confirmed**, **Corrected** (the claim was wrong), **Incomplete**
(true, but missing something that matters).

| # | Claim | Verdict | Evidence and correction |
|---|---|---|---|
| 1 | Colours live in `theme/LfColors.kt`. | **Incomplete** | Semantic colours are there (12 colours, each with a dark and a light value). But there are two more colour sources: **category colours** are *generated* in `chart/LfCategoryPalette.kt` (any hue, re-tinted to a fixed saturation and lightness per theme, lines 52–114), and the **Material colour scheme** is built in `theme/LfTheme.kt`. Also, `SPEC.md` §9.1 describes "a curated 16-swatch palette" that the code doesn't have, so the spec has drifted from the code. |
| 2 | Fonts (Inter, bundled) are in `LfFonts.kt`. | **Confirmed** | `LfFontFamily`, six weights cut from one variable file. The type *scale* (sizes, line heights) is in `LfTheme.kt`. Since `dd25dd9` (BUG61) the typography also carries right-to-left text direction and alignment (`LfTextAlign.kt`); the new system must keep that. |
| 3 | Type sizes, spacing, corner radius and animation timing are in `LfTheme.kt`. | **Confirmed**, with a caveat | The radii (`cornerSmall/Medium/Large`) live *inside* `LfSpacing`, next to the margins. Shape and spacing sharing one object is why `spacing.sm` could be used as a radius (row 9) without anything looking wrong. `LfSpacing` also holds layout thresholds (`minTouchTarget`, `actionMinWidth`, `segmentMinWidth`, `peekCardWidth`) that aren't style at all (§2). |
| 4 | Icons are in `icon/LfIcons.kt`. | **Confirmed** | Seven icons in the whole app: six stock Material (Home, List, Settings, Add, Close, Delete) and one hand-drawn (Analytics). No screen reaches for `Icons.*` directly. |
| 5 | No screen hardcodes colours. | **Confirmed** | No `Color(0x…)` or `Color.Red`-style value in any `feature/*`, `core/ui` or `app` source file. The only hex in screen files is sample *data* in previews (`0xFF3E6AD6` as a category's stored colour in Bin, Ledger and Categories previews), which is data, not styling. One colour is *derived*: `debit.copy(alpha = 0.12f)` in `InboxScreen.kt:363` (§2). |
| 6 | No screen hardcodes font sizes. | **Confirmed** | No `.sp` or `fontSize =` outside the design system. |
| 7 | Five fixed sizes exist: two 380 dp height limits and three 1 dp borders. | **Corrected** (undercounted) | Outside the design system there are **17 raw size uses** from 9 named constants and 2 literals (full list in §2). Missed: dialog list heights of **280** (Organise, 4 uses) and **320** (both pickers in `core/ui`); `HAIRLINE = 1` **defined three times** (Inbox, Ledger — reused by Bin — and `LfEntryRow`); `RAIL_THICKNESS = 1` (Organise tree lines); and the 1 dp border literals (Entry, Organise). Inside the design system there are ~16 more (chart sizes, borders, the button spinner's stroke, the FAB icon). |
| 8 | No screen sets its own font weights. | **Confirmed** | No `FontWeight.`, `TextStyle(` or `fontWeight =` in any screen. |
| 9 | Corner radius is inconsistent: cards 16, list rows and dialogs 24, buttons 16, text fields 8; Inbox and Ledger use spacing 8 as a radius; Home mixes 16 and 24. | **Confirmed, incomplete** | All true (`LfAtoms.kt:194/247/305/382`, `LfDialog.kt:45`, `LfEntryRow.kt:93`, `LedgerScreen.kt:487`, `BinScreen.kt:172`; spacing used as a radius at `InboxScreen.kt:364/405/409` and `LedgerScreen.kt:353/357`). Missing: chips 8, the segmented control 16 outside and 8 inside, the Organise field group 8 vs Entry's 16 for the same kind of box, chart bars and heatmap cells 8, the budget bar fully rounded, the FAB and category dots circular. **And a fourth radius from Material itself:** the date-picker dialogs and the Add bottom sheet use Material's built-in 28 dp, because `LfTheme` never sets Material's shapes. |
| 10 | Side margins: Inbox, Review, Deleted entries, Diagnostics use 16; most others 24; Ledger, Entry, Restore and Scan mix both. | **Confirmed, incomplete** | Organise also mixes both. The space at the **end of a list** varies too: 16 (Inbox, Bin), 32 (Analytics, Budgets), 48 (Ledger). |
| 11 | Five stock Material icons beside one custom icon in the bottom bar; the budget alert uses Android's stock warning icon. | **Corrected** (count) | The bar shows **four** stock icons (Home, List, Add inside the FAB, Settings) and **one** custom (Analytics); the full set is six stock plus one custom. The budget alert's `android.R.drawable.stat_sys_warning` is confirmed (`BudgetNotifications.kt:66`); the Inbox notifications already use the app's own `ic_lf_inbox_notification`. Re-checked after V2-3 merged: still the stock icon, and still no tap action. |
| 12 | The budget start-date and Analytics date-range pickers don't set colours and probably show lavender. | **Confirmed, and the cause is bigger** | Neither passes `colors` (`BudgetEditorDialog.kt:273`, `AnalyticsFilterSheet.kt:405`). Entry's picker colours only the dialog background (`EntryDialogs.kt:94`); Review's picker inside `LfDialog` sets nothing (`ReviewDialogs.kt:79`). **Root cause:** `LfTheme` maps only 10 of Material's colour roles (primary, onPrimary, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, error; `LfTheme.kt:262–284`). Every other role — the surface-container family, primary/secondary containers, tertiary, inverse, outlineVariant, scrim — keeps Material's purple baseline. Date pickers, range highlights, sheets and switches draw from some of those. Still needs checking on the phone (Phase A2 does it with a test and a device screenshot). |
| 13 | Detekt's magic-number rule is off for composables. | **Confirmed** | `config/detekt/detekt.yml:128–131`: `MagicNumber.ignoreAnnotated: ['Composable', 'Preview']`, **and** `ignorePropertyDeclaration: true`, so a file-level `private val X = 380.dp` is exempt even outside a composable. That's how every constant in §2 got past Detekt. |
| 14 | `bannedApiCheck` only looks for `!!` and `cacheDir`. | **Confirmed** | `build.gradle.kts:105–149`. |
| 15 | Screenshot tests seem to cover light theme only. | **Confirmed, measured** | 130 goldens at the audit, 135 after V2-3; exactly one is dark (`capture-guide-dark.png`). The harness (`core/testing/.../ScreenGoldens.kt:65`) takes font scale and layout direction but not theme; each test wraps `LfTheme` itself, nearly always in the default. Robolectric reports light, so "default" means light. |

**Found while checking (not in the audit):**
- Snackbars exist only in Inbox and Review (`InboxScreen.kt:95`, `ReviewScreen.kt:66`), and they're Material's unstyled default. `LfFonts.kt:120` still says no screen passes a snackbar host, which is out of date.
- No shared loading component exists. Loading is a spinner inside a button, a placeholder dash on Home, the word "Loading" in an empty-state card on Analytics, or nothing at all.
- The parallel V2-3 branch (Q2), since merged.

**Found by A1's dark goldens** (today's dark theme, not caused by A1):
- The Ledger row's bin icon is barely visible: a dark grey glyph on a dark row
  (`ledger-expenses-*-dark`). Fixed by the icon role in F4 or earlier in D3.
- Switches in the off position almost vanish (Payment apps' Paytm row,
  Settings' App lock and Block screenshots): Material's unchecked track uses a
  colour role `LfTheme` never sets. **Fixed in A2 as BUG63.**
- Disabled buttons ("24 words to go", a disabled Approve) are very faint.
  Below 4.5:1 is allowed for disabled controls, but these sit at the edge of
  readable; a `text.disabled` role (§4.2) settles it in B1/E1.

---

## 2. Every hard-coded size, sorted

**Layout constant** = a measured threshold or geometry that a new visual style
shouldn't change (touch-target minimums, a vector icon's internal grid).
**Token** = a design decision a new style *should* be able to change.

### Outside the design system (feature modules, `core/ui`, `app`)

| Value | Where | Kind | Becomes |
|---|---|---|---|
| `380.dp` `SHEET_MAX_HEIGHT` | `AnalyticsFilterSheet.kt:507` | **Token** | One `size.dialogListMax`. Five different numbers (380, 380, 280, 320, 320) do one job: how tall a scrolling list inside a dialog may get. That's a design decision, and having five copies is the defect. |
| `380.dp` `EDITOR_MAX_HEIGHT` | `BudgetEditorDialog.kt:341` | **Token** | same |
| `280` `PICKER_MAX_HEIGHT` ×4 | `TaxonomyDialogHost.kt:214/265/298/336` (constant at :390) | **Token** | same |
| `320` `PICKER_MAX_HEIGHT` | `core/ui/.../picker/LfPickerOption.kt:83` (:187) | **Token** | same |
| `320` `MULTI_PICKER_MAX_HEIGHT` | `core/ui/.../picker/LfMultiPickerDialog.kt:93` (:174) | **Token** | same |
| `HAIRLINE = 1` | `InboxScreen.kt:407` (:693), `LedgerScreen.kt:355/511` (:724), `BinScreen.kt:195` (reuses Ledger's), `core/ui/.../LfEntryRow.kt:113` (:383) | **Token** | `border.hairline`. Three copies of one value. `LedgerScreen.kt:511` (the accent outline on the draft being edited) becomes `border.emphasis`. |
| `1.dp` border | `EntryScreen.kt:348`, `CategoriesScreen.kt:371` | **Token** | `border.hairline` |
| `RAIL_THICKNESS = 1` | `CategoriesScreen.kt:261/271` (:344) | **Token** | `border.hairline` (the tree's connecting lines are a border role) |
| `DISCARD_TRACK_ALPHA = 0.12f` | `InboxScreen.kt:363` (:696) | **Token** (a colour) | `status.dangerSubtle` — the faint red behind a row being swiped to discard. A tint is a colour decision, so it belongs in the palette, not in a screen. |
| `RoundedCornerShape(spacing.sm)` | `InboxScreen.kt:364/405/409`, `LedgerScreen.kt:353/357` | **Token** (wrong kind used) | `shape.row` / `shape.control` (§4.2) |
| `0.dp` (indent off) | `AnalyticsScreen.kt:930` | **Layout constant** | stays (it means "no indent") |
| `UNSAVED_DETAIL_MAX_LINES = 2`, `NOTE_MAX_LINES = 1` | `LedgerScreen.kt:741`, `LfEntryRow.kt:384` | **Layout constant** | stays: how much text a row shows, not how it looks |

Everything else matching a "size" pattern outside the design system is OCR,
parsing or worker tuning (`PageQuad`, `ReceiptGeometry`, `MAX_PDF_SCALE`, …):
not UI, out of scope.

### Inside the design system

| Value | Where | Kind | Becomes |
|---|---|---|---|
| `minTouchTarget = 48.dp` | `LfTheme.kt` (`LfSpacing`) | **Layout constant** | moves to a separate `LfLayout` object; never changed by a style (SPEC §9.6) |
| `actionMinWidth = 112.dp`, `segmentMinWidth = 72.dp`, `peekCardWidth = 172.dp` | `LfSpacing` | **Layout constant** | `LfLayout`: measured breakpoints with recorded reasons (BUG9) |
| `MIN_FIELD_WIDTH = 96.dp` | `LfAmountField.kt:219` | **Layout constant** | stays in the component |
| `ICON_SIZE = 24`, `BAR_WIDTH`, `BASELINE` | `LfIcons.kt:49–50` | **Layout constant** | a vector's internal drawing grid, not a rendered size |
| `cornerSmall/Medium/Large` 8/16/24 | `LfSpacing` | **Token** | moves out of spacing into shape roles (§4.2) |
| `1.dp` borders | `LfAtoms.kt:248/308`, `LfChip.kt:48`, `LfSurfaces.kt:107` (divider) | **Token** | `border.hairline` |
| `2.dp` spinner stroke | `LfAtoms.kt:213` | **Token** (component) | `button.spinnerStroke` |
| `FAB_ICON_SIZE` | `LfNavigation.kt:117` | **Token** | `icon.size.lg` |
| Chart defaults: budget bar 8, donut 104 / 14, bar row 36, mirrored 132, stacked 168, treemap 160 | `chart/*.kt` | **Token** (component) | `chart.*` component tokens, so a denser or roomier style can change them in one place. The "a chart orients, the list is the content" rule stays (`CLAUDE.md` §5). |

---

## 3. Map of the UI layer

### Shared today

- **`:core:designsystem`** — theme (`LfColors`, `LfTheme`, `LfFonts`,
  `LfTextAlign`); components `LfScaffold`, `LfButton` (Filled / Outlined /
  Tonal / Text / Inline), `LfCard`, `LfTextField`, `LfDialog`, `LfChip`,
  `LfSegmentedControl`, `LfActionRow`, `LfAdaptiveRow`, `LfSwitchRow`,
  `LfIconButton`, `LfAmountField`, `LfCaptureGuide`, `LfBottomBar`, `LfFab`,
  `LfScreenTitle`, `LfEmptyState`, `LfDivider`, `LfCategoryDot`, `LfDetailRow`;
  charts `LfBudgetBar`, `LfDonutChart`, `LfHorizontalBarChart`,
  `LfMirroredBarChart`, `LfStackedBarChart`, `LfTreemap`, `LfCalendarHeatmap`
  (+ `LfAxisTicks`, `LfCategoryPalette`); formatting (`MoneyFormat`,
  `TimeStamp`, `Bidi`, `DisplayLocale`, `QuantityFormat`).
- **`:core:ui`** — `LfEntryRow`, `LfLineItemEditor`, `LfPhraseEntry`,
  `LfPhraseScanner`, `LfRecoveryKitWarningDialog`, `phraseSecret()`,
  `LfPickerDialog` + `LfChoiceRow`, `LfMultiPickerDialog` + `LfCheckRow`.

### Repeated: the same job built more than once

| Job | Separate versions today | Becomes |
|---|---|---|
| Section container | Analytics `SectionCard`, Organise `TaxonomyCard`, Diagnostics `DiagnosticsCard`, Budgets `BudgetCard`, Export `FileCard`/`StatusCard`, Scan `ResultCard`/`FailureCard`/`SavedCard`, Notification access `GrantCard`/`PrivacyCard`, Home's 9 cards | `LfSection` (heading + content + one action), built on `LfCard` |
| Inline message / banner | Ledger `MessageBanner`, Organise `MessageBanner`, Bin `BinMessage`, Onboarding `ErrorFooter`, Recovery `FailureMessage`/`ScanMessage`, Restore and Back up now `ResultMessage`, Entry's red `Text`, Home's four warning cards | `LfNotice` (info / success / warning / error), one design |
| Section heading | Home `SectionHeading`, Settings `SectionHeading`, Onboarding `StepHeading`, Organise `HiddenHeader`, Ledger `BandHeader` | `LfSectionHeader` |
| Pinned action bar | Recovery, Restore and Back up now `SubmitBar` (3 copies), Entry `SaveBar`, Export `ExportBar`, Onboarding `PrimaryActionBar`, Review actions, Organise `AddBar` | `LfActionBar` (part of the templates in §4.4) |
| Pickers in forms | Entry's `PickerDialog`/`DateDialog`/`SingleItemDialog` and Review's own copies of all three | shared form dialogs in `:core:ui` |
| Recovery Kit file picker | Onboarding and Back up now `RecoveryKitPicker` | one helper in `:core:ui` |
| List row | `LfEntryRow`, Ledger `PendingRow`/`CandidateRow`, Inbox `PendingRow`/`SwipeableRow`, Bin `BinRow`, Settings `SettingsRow`, Diagnostics `CountRow`/`StackedRow`, six Analytics rows, Organise `CategoryRow`/`ChoiceRow` | one row family `LfListRow` (leading mark, title, supporting line, trailing amount or control, actions). Keeps the per-row performance rules (`CLAUDE.md` §8: one `Layout`, drawn decorations) |
| Swipe-to-discard track | Inbox and Ledger, each with a spacing value as its radius | `LfSwipeRow` |
| Bordered field group | Entry (radius 16), Organise (radius 8) | `LfFieldGroup` |
| Smaller pairs | `Explanation`, `DetailRows`, `CategoryList`, `Header` (two copies each) | folded into the above |

### One-off (fine to stay unique, but styled from tokens)

The camera overlay (`LfCaptureGuide`, `PageQuad`), the 24-word grid, the
amount field, the charts, Inbox's filter band, Review's original-message panel,
Organise's tree lines, Analytics' period control, and V2-3's lock screen.

### Missing entirely

A loading component (skeleton), a progress screen for long blocking work, a
shared feedback host (snackbar) on every screen, a field-level error under an
input, an error-with-Retry pattern, and themed wrappers for the date pickers
and the bottom sheet.

---

## 4. Design system architecture

### 4.1 Three token layers, and why each exists

```
primitives (internal)  →  semantic roles (public)  →  component tokens (internal)
coral-500, space-4,       money.out, surface.raised,   card.radius, button.height,
radius-16, inter-600      screen.edge, shape.container  row.border, chart.donutSize
        ▲                           ▲                            ▲
   a visual direction          screens read these         components read these
   swaps this layer            (and nothing else)         (screens never see them)
```

- **Primitives** exist so that **one** place holds the raw values. A visual
  direction (Phase 2) is mostly a new set of primitives. They're `internal` to
  `:core:designsystem`, so a feature module *cannot compile* against them. That
  makes "screens never reference raw values" enforced by Kotlin itself, not by
  review.
- **Semantic roles** exist because screens should describe *meaning*, not
  appearance. A screen says "this is money going out"; the theme decides that
  this means coral in one direction and brick red in another, and a different
  shade in dark mode. Light and dark are two mappings from primitives to the
  same roles.
- **Component tokens** exist so a style can change one building block without
  touching the others: borderless cards with outlined buttons, say, or a denser
  row. They also make each component's styling testable ("a card's border is
  `border.hairline`"). They're `internal` too.

### 4.2 The semantic roles

**Colour** (`LfTheme.colors`)

| Group | Roles | Notes |
|---|---|---|
| Surfaces | `canvas`, `raised`, `overlay`, `sunken`, `inverse` | today: surfaceBase, surfaceRaised, surfaceOverlay |
| Text | `primary`, `secondary`, `muted`, `onAccent`, `onInverse`, `disabled` | `muted` = today's `textTertiary` (kept at ≥ 4.5:1 contrast) |
| Borders | `subtle`, `strong`, `focus` | `subtle` = today's `outline` |
| Accent | `default`, `pressed`, `subtle`, `on` | the colour of the main action and selection |
| **Money** | `out`, `outSubtle`, `in`, `inSubtle` | **Not** "danger"/"success". Spending isn't an error (`LfColors.kt` KDoc) and the two books never share a colour role (Law 2). |
| Status | `warning(+Subtle)`, `danger(+Subtle)`, `success(+Subtle)`, `info(+Subtle)` | `danger` is for destructive actions and failures only, never for an expense amount |
| Overlay | `scrim`, `focusRing` | |
| Data | `chart.grid`, `chart.axis`, `chart.categorical(id, stored)` | the category colour generator moves behind a role, so a direction can retune it, but its per-theme contrast guarantee stays |

**Space** (`LfTheme.space`): `screenEdge` (one value for every screen),
`sectionGap`, `itemGap`, `inlineGap`, `cardInset`, `rowInset`, `listEnd`,
`actionBarInset`. The same scale as today's `xs…xxl` underneath.

**Shape** (`LfTheme.shapes`): `container` (cards, sections, sheet top corners),
`row` (list rows), `control` (buttons, chips, segmented control, fields),
`dialog`, `full` (pills, dots). Five *roles*; a direction chooses whether some
share a value. Material's own `Shapes` are filled from these, so pickers and
sheets stop using Material's 28 dp.

**Type** (`LfTheme.type`): `display`, `title`, `heading`, `body`, `bodySmall`,
`label`, `caption`, `amountHero`, `amountRow`, `amountSmall`, `mnemonic`.
Tabular figures on every amount style (SPEC §9.2), the `ss02` disambiguation on
`mnemonic`, and BUG61's direction-aware alignment stay built in. The six-weight
registration in `LfFonts.kt` stays, for Android's bold-text setting.

**Border** (`LfTheme.borders`): `hairline`, `emphasis`. **Elevation**: `none`
for anything list-sized (the brief prefers a hairline border to a shadow), and
`overlay` for dialogs and sheets only.

**Motion** (`LfTheme.motion`): today's `micro`/`standard`/`emphasized`/
`fadeOut`/`axisSlide`, scaled by V2-3's animations setting (Full / Reduced /
Off).

**Icon** (`LfTheme.icons`): `size.sm/md/lg` (16/20/24), one stroke weight, and
a single icon *family* chosen in Phase 2. Every icon goes through `LfIcons`,
including notification icons, which become drawable resources generated from
the same shapes.

**Layout constants** (`LfLayout`, not a token, not changed by a style):
`minTouchTarget`, `actionMinWidth`, `segmentMinWidth`, `peekCardWidth`,
`dialogListMax` (once settled).

### 4.3 Where the tokens live and how screens use them

```
core/designsystem/src/main/java/com/ledgerflow/core/designsystem/
  theme/
    tokens/primitive/   LfPalette.kt, LfScale.kt, LfTypefaces.kt      (internal)
    tokens/semantic/    LfColorRoles.kt, LfSpaceRoles.kt, LfShapeRoles.kt,
                        LfTypeRoles.kt, LfBorderRoles.kt, LfMotion.kt (public)
    tokens/component/   LfButtonTokens.kt, LfCardTokens.kt, LfRowTokens.kt, …  (internal)
    LfThemeSpec.kt      one direction = primitives + light and dark role mappings
    LfTheme.kt          provides the roles; builds Material's ColorScheme,
                        Shapes and Typography *completely* from the roles
    LfLayout.kt         layout constants
  component/  pattern/  chart/  icon/  format/
```

- Screens write `LfTheme.colors.money.out`, `LfTheme.space.screenEdge`,
  `LfTheme.shapes.container`, `LfTheme.type.amountRow`: roles only. They mostly
  don't even need those, because templates and components apply them.
- The roles are `@Immutable` classes provided through
  `staticCompositionLocalOf`, as today, so reading a token costs nothing per
  frame (the 120 Hz rules in `CLAUDE.md` §8 stay intact). New types go in
  `compose-stability.conf` only once checked to be deeply immutable.
- **Migration without breakage:** today's names (`LfTheme.colors.debit`,
  `spacing.lg`, `cornerMedium`, …) stay as `@Deprecated(ReplaceWith(…))` aliases
  pointing at the new roles, so each screen moves in its own reviewable commit.
  Phase G removes them.

### 4.4 How v2 plugs in without rewriting tokens or components

1. **Templates, not screens, own the layout** (`pattern/` package):
   `LfTabPage`, `LfDetailPage`, `LfFormPage`, `LfFlowStep`, `LfSettingsPage`,
   `LfSheet`. Each has the same slots: header, notices, stateful content,
   pinned action bar. They apply `screenEdge`, `listEnd` and the action-bar
   inset, so margins *can't* drift apart again. **They know nothing about the
   bottom bar, tabs or routes** (those stay in `:app`, `LedgerFlowShell.kt`), so
   v2 can move a screen from a tab into Settings, or the other way round, as D7
   did, without touching its design.
2. **New features compose existing parts.** V2-3's pages are `LfSettingsPage`
   + `LfListRow`/`LfSwitchRow`; the lock screen is an `LfFlowStep`; the V2-5
   receipts browser is a new grid component; V2-7's goals, recurring items and
   debts are `LfDetailPage` + `LfSection` + rows + existing charts.
3. **A feature that needs a new look adds a component, not a style.** It's
   built in `:core:designsystem` from semantic roles, with goldens in both
   themes. A genuinely new *meaning* ("goal progress", say) becomes a new role,
   noted in the design-system guide, never a screen-local colour.
4. **Money meanings stay fixed.** Anything that is money out or money in uses
   `money.out`/`money.in`; anything else (a loan's balance, a goal's target)
   uses neutral text or the accent, so no new feature can blur the two books
   (Law 2).
5. **Outside Compose:** a future home-screen widget (V2-PLAN §6.7) or
   notification can't read Compose tokens, so the roles get exported as Android
   colour resources generated from the same primitives (a small Gradle task,
   added only when the first such surface is approved).

---

## 5. Operational UX system

### 5.1 One state model for every screen

Each screen's content area is in exactly one of these states. A shared
`LfStateContent` component draws them, so they look the same everywhere.

| State | When | What it looks like |
|---|---|---|
| **Loading** | data not ready yet | Nothing for the first 300 ms (most loads finish sooner, and flashing a placeholder is worse than waiting). Then **skeleton rows** in the layout's own shape, so nothing jumps when the data arrives (Home already keeps its height this way). If still loading at 10 s: a line saying what it's doing. **Never an endless spinner.** |
| **Long blocking work** | setup, restore, upgrade, backup | A progress screen (`LfFlowStep`) with what is happening, a progress indicator, and what is safe ("your data is untouched") |
| **Empty** | nothing to show | Heading + one sentence + at most one action, inside the content area (header and actions stay). Four kinds, with different words: **first use** ("No budgets yet"), **no matches** ("Nothing matched these filters" + Clear filters), **outside the window** ("Nothing in the last N days"), **all done** ("Inbox clear") |
| **Error** | something failed | An `LfNotice` in the content area: what happened, what is safe, and **Retry** when retrying can help. Blocking errors take the whole content area but always keep a way forward or out. **Rule: no error state without an action** (tested, §6.6) |
| **Unavailable** | the app can't do this right now, through no fault of the data | Same notice style, with the fix as the action: data not open, a permission missing (camera, notifications), capture stopped, storage folder lost |
| **Offline** | no network | Doesn't apply to anything shipped today: nothing in the app uses the network (Law 6). V2-4's Drive sync and D2's converter will; for those surfaces only, "Unavailable · no connection" with Retry. Local data never shows an offline state. |
| **Ready** | content | the screen itself |

### 5.2 Feedback for actions

| Situation | Pattern |
|---|---|
| A reversible action (delete to bin, discard to the Discarded tab) | Do it immediately; snackbar "Moved to bin · Undo" |
| An irreversible action (erase for good, delete all receipts) | `Warning` dialog that names the count and says it can't be undone (exists today; keep) |
| A save that closes the screen | Snackbar on the screen you land on: "Saved to Expenses" |
| A result that matters later (backup written, export done) | A success notice that stays on the screen |
| A failure | Snackbar or notice with what happened and Retry; never a silent no-op (Budgets today) |
| Work in progress | The button shows a spinner and keeps its label; it can't be tapped twice; the rest of the screen stays usable |

One `LfFeedbackHost` sits in `LfScaffold`, so **every** screen can show a
snackbar, styled from tokens and announced to screen readers (live region).
No `Toast` (`CLAUDE.md` §9).

### 5.3 Validation

- Check when the user taps the action or leaves a field, not on every
  keystroke. The amount field still filters what can be typed.
- The message goes **directly under the field** (`LfTextField` gains an error
  line). If that field is scrolled out of view, the pinned bar says "1 thing to
  fix" and tapping it scrolls there. That fixes Entry's message sitting below
  the fold.
- Buttons stay tappable when the reason can be explained (the tap shows it);
  they're only disabled while work is running.
- Standard messages: amount must be above zero; a name can't be blank or a
  duplicate; a phrase word must be on the list (exists; the checksum is checked
  before the slow key derivation, which stays).

### 5.4 Every screen in `SCREENS.md`, mapped

Template = the layout it gets (§4.4). States = which states from §5.1 it must
handle (L loading, E empty, X error, U unavailable, S success feedback). Gap =
a known audit gap closed in Phase E.

| `SCREENS.md` entry | Template / component | States | Gap fixed |
|---|---|---|---|
| Unlocking | `LfFlowStep` (progress) | L | bare text → progress state |
| Choose your currency | `LfFlowStep` | — | |
| Your recovery phrase | `LfFlowStep` | — | |
| Confirm your phrase | `LfFlowStep` | X (field errors) | |
| Save your Recovery Kit | `LfFlowStep` | X, S | |
| Recovery Kit warning (dialog) | `LfDialog` Warning | — | |
| Where should backups go? | `LfFlowStep` | X | "Not now" gets the promised warning |
| Setting up / You're set up | `LfFlowStep` (progress) | L, X | **dead end → Retry, and no "You're set up" when it failed** |
| Onboarding error (state) | `LfNotice` | X | |
| Restore a backup | `LfFlowStep` | L, X, S | |
| Finish restoring | `LfFlowStep` | L, X, S | |
| Recovery | `LfFlowStep` | L, X | |
| QR phrase scanner | capture one-off | U (camera) | |
| Updating your ledger | `LfFlowStep` (progress) | L | |
| Update blocked | `LfFlowStep` (error) | X | |
| Notification capture explainer | `LfFlowStep` | U, S | |
| Home | `LfTabPage` | L, E, U, S | |
| Home banners | `LfNotice` | U, X | four bespoke cards → one notice |
| Home empty state | `LfStateContent` empty | E | |
| Ledger | `LfTabPage` | L, E, X, S | blank while loading; delete gets Undo |
| Delete entry (dialog) | replaced by Undo snackbar (a soft delete is reversible) | S | |
| Discard unsaved entry (dialog) | `LfDialog` Warning (irreversible) | — | |
| Ledger empty states | `LfStateContent` empty (first use / outside window) | E | |
| Analytics | `LfTabPage` | L, E, X | "Loading" as an empty card → skeleton |
| Analytics empty states | `LfStateContent` empty (4 kinds) | E | |
| Filters (dialog) | `LfSheet` | — | |
| Custom range (dialog) | `LfSheet` | X | |
| Date-range picker | `LfDateRangePickerDialog` | — | lavender → themed |
| Settings | `LfTabPage` / `LfSettingsPage` | L | |
| Delete receipts (dialog) | `LfDialog` Warning | S | |
| Add sheet | `LfSheet` | — | Material 28 dp → shape role |
| Add entry | `LfFormPage` | X (fields), S | error below the fold; silent save → snackbar |
| Entry pickers / date / Start fresh? / Discard ₹X? / Discard the items? | shared form dialogs, `LfDatePickerDialog` | — | duplicate of Review's → shared |
| Scan a receipt | capture one-off + `LfFormPage` result | L, U (camera), X, S | |
| Inbox | `LfDetailPage` | L, E (all done / per tab), X, S | blank while loading |
| Inbox erase confirmation | `LfDialog` Warning | — | |
| Inbox empty states | `LfStateContent` empty | E | |
| Review | `LfFormPage` | L, X, S | empty form flashes before load |
| Review pickers / date / Discard the items? | shared form dialogs | — | |
| Not here any more | `LfStateContent` empty with action | E | |
| Notification capture | `LfSettingsPage` | U, S | |
| Organise | `LfDetailPage` (tabs) | L, E, X, S | |
| Organise's 7 dialogs | `LfDialog` / form dialog | X (name errors) | |
| Organise empty states | `LfStateContent` empty | E | |
| Budgets | `LfDetailPage` | L, E, X, S | **messages never shown; one-tap delete gets a confirmation; on-screen Back** |
| Budgets empty state | `LfStateContent` empty | E | |
| Budget editor, category/subcategory picker, start date | form dialog, `LfDatePickerDialog` | X | lavender → themed |
| Back up now | `LfFormPage` | L, X, U (no folder), S | on-screen Back |
| Export | `LfFormPage` | L, X, S | |
| Export without encryption? | `LfDialog` Warning | — | |
| Deleted entries | `LfDetailPage` | L, E, X, S | blank while loading |
| Bin erase confirmation | `LfDialog` Warning | — | |
| Bin empty state | `LfStateContent` empty | E | |
| Diagnostics | `LfDetailPage` | L, U, E | blank while loading |
| Payment to review / "N payments" / Could not approve (notifications) | notification style: app icon, accent colour | — | |
| Budget alert (notification) | same | — | stock icon → app icon; tap opens Budgets (re-check against V2-3's changes) |
| LedgerFlow is locked (V2-3) | `LfFlowStep` | U (unlock unavailable) | |
| Settings' V2-3 rows: switches, inline choices, read-only Base currency | `LfSettingsPage` rows (`LfListRow`, `LfSwitchRow`, choice row) | S | three row kinds → one row family |
| Options (dialog, V2-3) | `LfDialog` with choice rows | — | |
| Payment apps (V2-3) | `LfSettingsPage` | L ("Reading the apps…"), E, S | |
| Add an app? (dialog, V2-3) | `LfDialog` Warning | — | |

### 5.5 Out of scope (needs the data layer or new features)

- Editing a saved entry (no update path exists for a committed `ledger_entry`;
  it needs a use case and a Law 1 review).
- Undoing a budget delete (it's a hard delete in the repository). This plan
  adds a confirmation instead.
- Catching storage failures *inside* repositories. This plan catches them in
  ViewModels and shows an error state; the repositories stay as they are.
- A receipts viewer (V2-5) and a Licences/About screen (V2-3 §3.8).

---

## 6. Enforcement: keeping the inconsistency from coming back

1. **Kotlin visibility.** Primitives and component tokens are `internal` to
   `:core:designsystem`. A screen can't use `coral-500` because it can't see
   it.
2. **`designTokenCheck`**, a Gradle task modelled on `bannedApiCheck` (plain
   pattern matching, **no new dependency**), wired into `preMergeCheck`. It
   scans `feature/*`, `core/ui` and `app` main sources and fails on:
   - colour values: `Color(0x…`, `Color(red…`, `Color.Red`-style constants,
     `.copy(alpha =`;
   - sizes: a number before `.dp`/`.sp`, `Dp(`, and `CONSTANT.dp` (catches
     `HAIRLINE.dp`);
   - shapes: `RoundedCornerShape(`, `CircleShape`, `CutCornerShape(`;
   - type: `FontWeight.`, `TextStyle(`, `fontSize =`, `letterSpacing =`;
   - reading Material's theme directly: `MaterialTheme.colorScheme`,
     `.typography`, `.shapes`;
   - icons: `Icons.`, `ImageVector.Builder`, `painterResource(` outside
     `LfIcons`, and `android.R.drawable` in notification builders;
   - raw Material widgets that have an `Lf` version: `AlertDialog(`,
     `DatePickerDialog(`, `DatePicker(`, `DateRangePicker(`,
     `ModalBottomSheet(`, `Snackbar(`, `Scaffold(`, `TopAppBar(`, the `Button`
     and `TextField` families, `Card(`, `Switch(`.

   Exceptions live in `config/design-tokens-allowlist.txt`, one line per file
   and pattern with a reason. It starts with today's real violations (§2) and
   may only shrink: the task fails if the list grows without the same commit
   touching the allowlist, so every new exception gets reviewed. A custom
   Detekt or Lint rule would give better error messages but needs a new
   dependency (`detekt-api` / `lint-api`, `CLAUDE.md` §10), so it's left as an
   optional later step.
3. **Screenshots in both themes.** *(Built in A1.)* Each goldens test class has
   a one-line dark twin carrying `DarkGoldensRule` (`core/testing`); the
   harness then renders in night mode and saves `<name>-dark.png` beside the
   unchanged light `<name>.png` (alongside the existing `-1x`/`-2x` and `-rtl`).
   A rule rather than a class-level `+night` qualifier, because every
   method-level `@Config(qualifiers = PHONE_2X)` would replace the qualifier
   and quietly record the 2x goldens light. The Gradle task
   `goldenThemePairsCheck`, in `preMergeCheck`, fails when either half of a
   pair is missing, so new screens can't skip dark. Tolerance stays
   `LfScreenshotOptions` (`maxDistance 0.055`; never raised, `CLAUDE.md` §4).
   Goldens are reviewed, never re-recorded blind (§12).
4. **Material widgets inherit the theme.**
   - `ColorSchemeCompletenessTest` walks every property of Material's
     `ColorScheme` and fails if any still equals Material's built-in value or
     doesn't come from a role. Every slot is mapped, so a Material widget added
     in v2 inherits LedgerFlow's colours automatically.
   - `ShapesCompletenessTest` does the same for Material's `Shapes`.
     `LfFontsTest` already does it for `Typography`.
   - `LfDatePickerDialog`, `LfDateRangePickerDialog`, `LfBottomSheet` and
     `LfSnackbarHost` wrappers get goldens in both themes, and point 2 forbids
     the raw Material versions anywhere else.
5. **Contrast.** The existing `LfContrast` test grows to every text-on-surface
   role pair, money and status colours on their subtle backgrounds, and
   category colours against both surfaces, in both themes and every direction.
6. **No dead ends.** For each screen, a unit test drives its UI state through
   every error and unavailable case and asserts there's at least one action.
   It's named per Law 7 where it comes from a bug
   (`BugNN_SetupFailureOffersRetryTest`; the number is the next free one at the
   time).
7. **Written rules.** A short `docs/DESIGN_SYSTEM.md` usage guide, with
   `CLAUDE.md` §5 pointing to it (edited in Phase G, with your approval, since
   it's the agents' manual).

---

## 7. Implementation order (Phase 3)

**Before Phase 3:** answers to Q1–Q5; V2-3 merged and this branch rebased (Q2);
a direction chosen in Phase 2 (needed from Phase F only). All three were met on
2026-10-25.

Each step is one commit, sized for one review. After each one, I stop and
report, and wait for your review before the next.

**Checks after every step** (Windows, PowerShell):

```powershell
.\gradlew preMergeCheck                     # both flavours: build, unit tests, detekt, lint, bannedApiCheck, moduleDependencyRuleCheck (+ designTokenCheck from C1)
.\gradlew verifyRoborazziSmsFullDebug       # goldens; light and dark from A1 on
& "$((Get-Item (Get-Command git).Source).Directory.Parent.FullName)\bin\bash.exe" scripts/guard-schema.sh
& "$((Get-Item (Get-Command git).Source).Directory.Parent.FullName)\bin\bash.exe" scripts/guard-version.sh
& "$((Get-Item (Get-Command git).Source).Directory.Parent.FullName)\bin\bash.exe" scripts/guard-workflows.sh
```

Plus, where a step says so:
- the Compose stability report (`assembleSmsFullRelease -Pledgerflow.enableComposeCompilerReports=true`) when component parameters change;
- `installSmsFullDebug` on the phone, with a force-stop → relaunch, font scale 2.0, RTL, light and dark, for every step with a visible change;
- `:benchmark` scroll and startup comparisons when rows or the Ledger, Inbox or Home lists change (cool, interleaved, within the 20–25 min budget).

A 25-second green `preMergeCheck` means "cached", not "tested". Where a step
changes many modules I run it uncached once (`--no-build-cache --rerun-tasks`),
one gate at a time to stay inside the dev box's RAM and heat limits.

| Step | What changes | Visible? | Extra checks | Commit (example) |
|---|---|---|---|---|
| **A1** ✓ | Dark twin for every goldens test (`DarkGoldensRule`); 133 dark goldens recorded; `goldenThemePairsCheck` in `preMergeCheck` (§6.3). The four shell-hosted tab screens, Budgets and the component tests now render on the app background (`LfScaffold` / `surfaceBase`) instead of the bare test window, which moves 64 light goldens from `#FAFAFA` to `#F7F8FA` (≤ 3 levels per channel, checked by script) | No app change | review all dark goldens by eye | `test: dark-theme goldens for every screen, and a check that none is missing` |
| **A2** ✓ | Material `ColorScheme` (all 48 roles) and `Shapes` (all 5) mapped from today's palette and radii (`LfMaterialTheme.kt`); `LfMaterialThemeTest` completeness tests; BUG63 (off switch) fixed with `Bug63_OffSwitchIsVisibleTest`; `material-date-picker` and `material-selection-controls` goldens in both themes. Goldens changed only at the switches (Settings, Payment apps) | Yes, small (switches; pickers and sheet off-golden) | the phone wasn't connected, so the pickers were checked through the new goldens; a device pass of both pickers and the Add sheet is still owed | `fix: BUG63 -- Material widgets inherit LedgerFlow's colours and corners; the off switch is visible again` |
| **B1** | Primitive and semantic layers with **today's exact values**; old names become deprecated aliases | **No: every golden must be pixel-identical** | stability report | `refactor: design tokens in three layers, same values` |
| **B2** | Component tokens for the existing components (button, card, dialog, chip, segmented control, field, entry row, charts) | No: pixel-identical | stability report; `inkDifferences` on rows | `refactor: components read component tokens` |
| **B3** | Radii move out of `LfSpacing` into shape roles; `LfLayout` holds the layout constants | No: pixel-identical | | `refactor: shapes and layout constants leave LfSpacing` |
| **C1** | `designTokenCheck` + allowlist of today's violations, in `preMergeCheck` | No | run it against a deliberately bad file | `build: designTokenCheck rejects raw colours, sizes, shapes and widgets outside the theme` |
| **C2** | `LfDatePickerDialog`, `LfDateRangePickerDialog`, `LfBottomSheet`, `LfSnackbarHost` wrappers; the 4 pickers and the Add sheet move to them; allowlist shrinks | No (A2 already themed them) | goldens of each wrapper, both themes | `refactor: pickers and sheets go through themed wrappers` |
| **D1** | Templates (`LfTabPage`, `LfDetailPage`, `LfFormPage`, `LfFlowStep`, `LfSettingsPage`, `LfSheet`, `LfActionBar`) with today's look; **one screen edge margin and one list end** | Yes (margins) | device: insets with `uiautomator dump` + nav bar `mFrame`, before/after in the commit (`CLAUDE.md` §5) | `feat(ui): screen templates; one edge margin everywhere` |
| **D2** | Screens adopt templates, a few per commit (tabs · Inbox, Review, Entry · Settings pages · onboarding flows · Scan, Export, Diagnostics, Bin, Budgets, Organise) | Yes | per batch: goldens reviewed, 2.0, RTL | `refactor(inbox): Inbox and Review use the detail and form templates` |
| **D3** | Shape and border roles applied consistently (cards, rows, fields, swipe tracks); hairline and dialog-height tokens; allowlist shrinks | Yes (corners) | | `fix(ui): one corner shape per role; hairlines from the theme` |
| **E1** | `LfStateContent`, skeletons, `LfNotice`, `LfFeedbackHost`, field error line, progress step, all with goldens in both themes | No (components only) | contrast test | `feat(ui): shared loading, empty, error and feedback components` |
| **E2–E6** | Screens adopt the state system in the D2 batches, closing §5.4's gaps. Each fix gets a Law 7 test named after it: **setup dead end first**, then Budgets' messages and delete confirmation, Entry's off-screen error, blank loading screens, the missing on-screen Back, the budget alert icon and tap | Yes | no-dead-end tests; device run of each fixed path | `fix(onboarding): BUGNN -- a failed setup offers Retry instead of "You're set up"` |
| **F1** | Docs only: ADR-0031 (the chosen direction), `SPEC.md` §9 rewritten to match, `V2-PLAN.md` §8 updated | No | | `docs: ADR-0031 visual direction; SPEC §9 follows` |
| **F2** | The chosen direction's primitives (and font file, if approved) replace today's; light and dark | **Yes: every golden** | contrast for every pair; review goldens screen by screen; device in both themes | `feat(ui): <direction> palette and type` |
| **F3** | Component token changes for the direction (density, borders, shapes) | Yes | stability report; benchmarks on Ledger and Inbox | `feat(ui): <direction> component styling` |
| **F4** | One icon family through `LfIcons`; notification icons generated from it | Yes | notification shade screenshots | `feat(ui): one icon set, notifications included` |
| **F5** | Screen-level composition the direction calls for (Home first, then the D2 batches) | Yes | per batch: goldens, 2.0, RTL, device, benchmarks for lists | `feat(home): Home in <direction>` |
| **G** | Deprecated aliases removed; allowlist empty; `docs/DESIGN_SYSTEM.md`; `CLAUDE.md` §5 and `SPEC.md` pointers; `LfFonts.kt`'s stale snackbar note fixed | No | full uncached gate | `chore(ui): remove legacy theme names; design-system guide` |

### Risks and how each is handled

| Risk | Handling |
|---|---|
| Golden churn hides real regressions | B-steps must be pixel-identical; visible steps are small, and goldens are reviewed per screen (`CLAUDE.md` §12). The known red receipt-corpus job is read by test name so it can't hide new red (memory). |
| Merge conflicts with V2-3 | Q2: merge it first. |
| Row cost at 120 Hz | Rows keep the one-`Layout` design; token reads are static; benchmarks on Ledger, Inbox and Home lists (`CLAUDE.md` §8; "fewer nodes is not cheaper", so measure). |
| Font scale 2.0, RTL, BUG9 label wrapping, BUG49 nested scaffolds | Templates build on `LfScaffold` and `LfActionRow`; the existing structural checks run on every golden; device check at 2.0 and RTL for visible steps. |
| Contrast regressions in a new palette | §6.5 contrast test across every role pair, both themes. |
| Palette decision (Q1) | No palette change lands before F1's ADR and spec update. |

---

## Phase 2 preview (after this plan is approved)

Two or three directions, each in its own Design canvas: Home, one detail screen
(Review), one form (Add entry), and the loading, empty and error states, all
built from the roles in §4.2 so the mockups translate straight into tokens.
Plain-language reasoning, a recommendation, and your choice. The earlier
Home-only direction canvas is a sketch; Phase 2 replaces it.
