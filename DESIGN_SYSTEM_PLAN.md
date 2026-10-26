# LedgerFlow design system — Phase 1 plan

Branch `redesign/design-system`, cut from `origin/main` at `09b28ff` (2026-10-25)
and fast-forwarded to `0cc93ea` once V2-3 (PRs #8–#12) was merged.

**Status (2026-10-25):** plan approved; Q1–Q5 answered as recommended; Phase 2
done and the owner chose **B · Night Vault**
(canvas: https://claude.ai/artifact/BHYXqJ93MQQKV9CJErQJ7N). Phase 3 starts at
step A1. **Amended 2026-10-25** after the owner supplied reference samples:
Night Vault keeps its colours and fonts and takes its shapes, components and
layouts from the samples; Profile replaces the Settings tab (see below, and
Q6–Q9).

### Reference samples (2026-10-25)

`D:\LF_UI_UX_design_sample` (outside the repo): `cards.webp` is a component
sheet; `pfp.png` is a Profile screen; the screenshots show transactions,
account, language, a logout sheet, login, notifications, an onboarding
carousel, settings, security, reset password, a verification code, PIN setup
and sign-up. The owner's brief: take ideas from them; take navigation,
buttons, controls and forms from `cards.webp`; ignore its iOS kit; make
charts in the same spirit but not copies.

**Taken from the samples:**
- **Shapes and sizes:** 16 dp corners on buttons, fields, tiles and grouped
  panels; 24 dp on sheets and dialogs; full rounding on pills, tabs, the FAB,
  avatars and progress bars (§4.2).
- **Buttons:** large (56 dp) and small (40 dp); filled, tonal, outlined (the
  Google sign-in style), icon-only and pill.
- **Fields:** 56 dp tall, hairline border, placeholder inside, a trailing eye
  on passwords, an attachment field.
- **Controls:** checkbox, radio, switch, pill-shaped segmented tabs, slider.
- **Rows:** three kinds. A navigation row (icon tile, label, current value,
  chevron), a select row (check on the chosen one) and a switch row.
- **Sheets:** a grab handle; a confirm sheet with a No/Yes pair; option tiles
  (Camera, Image, Document: today's Add sheet); a filter sheet with chip
  groups and Apply; recurrence pickers.
- **Navigation:** a bottom bar with the + raised half above it and labels
  under every icon; a top bar with Back, a centred title and an overflow menu.
- **Pieces:** tinted icon tiles; the profile header (avatar with a ring, name,
  edit); progress bars; a filter bar (period pill and filter icon); the "See
  your financial report" link banner.

**Not taken:**
- The violet palette. Night Vault's colours stay (owner, 2026-10-25).
- The iOS kit (status bar, keyboards). Android draws its own.
- The graphs as drawn: no multi-hue gradient donut, since colour carries
  meaning here. §4.5 has the chart style that replaces them.
- One card per transaction row. The compactness brief (`CLAUDE.md` §5) keeps
  rows grouped in one panel with hairlines, as `pfp.png` itself does.
- The 3D illustrations: no artwork exists (Q9).

**Sample roles in Night Vault colours:**

| In the samples | In Night Vault |
|---|---|
| violet filled button | bone `action.primary`, graphite text |
| lavender tonal button | `surface.overlay` with bone text |
| outlined (Google) button | transparent, `border.strong` hairline |
| pastel icon tile | the category's colour at `SUBTLE_TINT`, glyph in the category colour |
| red logout tile and text | `status.dangerSubtle` / `status.danger` (pink, never money-out coral) |
| violet switch, check, radio | `action.primary` |
| green progress bar | `money.incoming` only for money in; budgets and goals use `action.primary`, over-budget `status.warning` |
| white card on grey page | `surface.raised` on `surface.canvas`, hairline `border.subtle` |

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
| **Semantic token** | A value named by its job: `money.outgoing`, `surface.raised`, `text.muted`. Screens use these. |
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
| Q6 | **Decided 2026-10-25: accounts are design-only.** The samples have sign-up, login, reset password and a verification code. Those need a server to check them, and V2-PLAN D3 decided LedgerFlow has no accounts (Law 6). | Building them would reverse D3. | The screens are drawn on the canvas and mapped in §5.6 as "waits on D3". Until D3 changes, "sign in" means Google, for Drive backup (D1), and "log out" means "Sign out of Google Drive", shown only when Drive is linked. |
| Q7 | **Decided 2026-10-25: an in-app PIN is an app-lock option.** The samples set up a 4-digit PIN. | A PIN is a user-chosen secret, and `CLAUDE.md` §0 bans one anywhere on the key path. | Offered beside the phone's own lock (fingerprint, face, screen lock). It hides the screens only, like today's lock: never the DEK, the Keystore or a backup (`CLAUDE.md` §7, `AppLockStateTest`). Its own ADR, at the next free number, when it is built. |
| Q8 | **Decided 2026-10-25: Profile replaces the Settings tab.** The bar becomes Home · Ledger · + · Analytics · Profile; Settings and Account live inside Profile. | Supersedes V2-PLAN D7's placement (Settings as the fourth tab). | Step D4 builds it, and updates `SPEC.md` §9.3 and V2-PLAN D7 in the same commit. |
| Q9 | **Artwork for a welcome carousel.** The samples' onboarding uses 3D illustrations; LedgerFlow has none. | A bundled image with a licence, like the fonts (Law 6: never downloaded). | Decide when general-user onboarding (V2-PLAN §6.9) is picked. Recommend flat vector illustrations in Night Vault tones, with their licence in `assets/licenses/`. Until then, a carousel shows no art. |

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

**Needed by the reference samples (2026-10-25).** Each is built when the step
or V2 phase that first uses it arrives (§7), from the canvas, in both themes:

| Part | What it is | First needed by |
|---|---|---|
| `LfIconTile` | 40 dp rounded square, the category or row colour at `SUBTLE_TINT`, glyph or initial drawn in a leaf (the `lfCategoryDot` technique, so a row stays one `Layout`) | F3 (entry rows), D4 (Profile rows) |
| `LfNavRow` | icon tile, label, current value, chevron; the settings-index row | D4 |
| `LfSelectRow` | label with a check on the chosen row (Language, Security, the Options dialog) | D4 |
| `LfProfileHeader` + `LfAvatar` | 64 dp avatar with a 2 dp ring (photo or initial), name, edit button | D4 (placeholder), V2 §3.1 |
| `LfProgressBar` | `LfBudgetBar`'s bar generalised: full-round 8 dp, a role for its colour | E1 |
| `LfSheet` variants | confirm (title, line, No/Yes pair), options (icon tiles), filter (chip groups, Reset, Apply) | E1 |
| `LfFilterBar` | period pill (opens a picker) and a filter icon | F5 (Ledger, Analytics) |
| `LfLinkBanner` | one-line panel with a chevron ("See your financial report") | F5 (Home) |
| `LfPasswordField` | `LfTextField` with a trailing show/hide eye | V2 auth (Q6) |
| `LfSocialButton` | outlined button with a brand mark ("Continue with Google") | V2-4 Drive (D1) |
| `LfCodeInput` | 6 digit slots, the filled ones in Plex Mono | V2 auth (Q6) |
| `LfPinPad` + `LfPinDots` | 3×4 keypad and 4 dots, full screen | PIN lock (Q7) |
| `LfPagerDots` | carousel position | onboarding (§6.9) |
| `LfAreaChart` | §4.5 | F6 |

---

## 4. Design system architecture

### 4.1 Three token layers, and why each exists

```
primitives (internal)  →  semantic roles (public)  →  component tokens (internal)
coral-500, space-4,   money.outgoing, surface.raised,  card.radius, button.height,
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
| Action | `primary`, `onPrimary`, `pressed`, `subtle` | the main action and the selection. *(Built in B1 as `action`, not `accent`: `colors.accent` is the old flat name, kept as an alias until step G.)* |
| **Money** | `outgoing`, `outgoingSubtle`, `incoming`, `incomingSubtle` *(not `out`/`in`: `in` is a Kotlin keyword)* | **Not** "danger"/"success". Spending isn't an error (`LfColors.kt` KDoc) and the two books never share a colour role (Law 2). |
| Status | `warning(+Subtle)`, `danger(+Subtle)`, `success(+Subtle)`, `info(+Subtle)` | `danger` is for destructive actions and failures only, never for an expense amount |
| Overlay | `scrim`, `focusRing` | |
| Data | `chart.grid`, `chart.axis`, `chart.categorical(id, stored)` | the category colour generator moves behind a role, so a direction can retune it, but its per-theme contrast guarantee stays |

**Space** (`LfTheme.space`): `screenEdge` (one value for every screen),
`sectionGap`, `blockGap` (between the blocks of a one-task step; added in B3
for the phrase entry's 24 dp), `itemGap`, `inlineGap`, `cardInset`,
`rowInset`, `listEnd`, `actionBarInset`. The same scale as today's `xs…xxl`
underneath.

**Shape** (`LfTheme.shapes`): `container` (cards, sections, sheet top corners),
`row` (list rows), `control` (buttons, chips, segmented control, fields),
`dialog`, `full` (pills, dots). Five *roles*; a direction chooses whether some
share a value. Material's own `Shapes` are filled from these, so pickers and
sheets stop using Material's 28 dp.

Night Vault's values, from the reference samples (2026-10-25; they replace the
12 dp panels and 10 dp buttons first drawn on the canvas):

| Role | Value | Used by |
|---|---|---|
| `container` | 16 dp; sheets 24 dp on their top corners | cards, grouped panels, sections |
| `row` | 0 inside a panel; 16 dp for a standalone row | list rows |
| `control` | 16 dp; 12 dp on the 40 dp icon tile (16 would make it read as a circle) | buttons, fields, icon tiles |
| `dialog` | 24 dp | dialogs, sheets |
| `full` | 50 % | pills, chips, segmented tabs, the FAB, avatars, progress bars, PIN dots |

**Size** (each component's tokens, since a direction changes them; F3 sets these): button 56 dp (large) and 40 dp (small); field
56 dp; icon tile 40 dp; avatar 64 dp with a 2 dp ring; row minimum 56 dp;
bottom bar 64 dp with a 56 dp + raised half above it. The 48 dp touch target
stays the floor for anything smaller.

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
`categoryDotSize` (the swatch `:core:ui`'s entry row draws into), and
`dialogListMax` (once settled). A plain object, not a theme value: no direction
changes a threshold that decides when a control stacks.

### 4.3 Where the tokens live and how screens use them

```
core/designsystem/src/main/java/com/ledgerflow/core/designsystem/
  theme/
    primitive/          LfPrimitives.kt: LfPalette, LfScale, LfTypeScale (internal)   [built in B1]
    tokens/semantic/    LfColorRoles.kt, LfSpaceRoles.kt, LfShapeRoles.kt,
                        LfTypeRoles.kt, LfBorderRoles.kt, LfMotion.kt (public)
    tokens/component/   LfButtonTokens.kt, LfCardTokens.kt, LfRowTokens.kt, …  (internal)
    LfThemeSpec.kt      one direction = primitives + light and dark role mappings
    LfTheme.kt          provides the roles; builds Material's ColorScheme,
                        Shapes and Typography *completely* from the roles
    LfLayout.kt         layout constants
  component/  pattern/  chart/  icon/  format/
```

- Screens write `LfTheme.colors.money.outgoing`, `LfTheme.space.screenEdge`,
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
   debts are `LfDetailPage` + `LfSection` + rows + existing charts. The
   samples' screens compose the parts in §3's "Needed by the reference
   samples" table: Profile is `LfProfileHeader` + `LfNavRow`s, sign-in is
   `LfFormPage` + `LfPasswordField` + `LfSocialButton`, the PIN is
   `LfFlowStep` + `LfPinPad`. §5.6 maps every screen V2-PLAN and the samples
   add, one by one.
3. **A feature that needs a new look adds a component, not a style.** It's
   built in `:core:designsystem` from semantic roles, with goldens in both
   themes. A genuinely new *meaning* ("goal progress", say) becomes a new role,
   noted in the design-system guide, never a screen-local colour.
4. **Money meanings stay fixed.** Anything that is money out or money in uses
   `money.outgoing`/`money.incoming`; anything else (a loan's balance, a goal's target)
   uses neutral text or the accent, so no new feature can blur the two books
   (Law 2).
5. **Outside Compose:** a future home-screen widget (V2-PLAN §6.7) or
   notification can't read Compose tokens, so the roles get exported as Android
   colour resources generated from the same primitives (a small Gradle task,
   added only when the first such surface is approved).

### 4.5 Chart style

The samples' graphs set the tone (a soft line over a fading fill, a ring with
a total in the middle), not the drawing. Every rule in `CLAUDE.md` §5's charts
section still applies: hand-rolled `Lf*` Canvas code with no charting
dependency (ADR-0005), one book per chart (Law 2), pre-binned to the pixel
width (SPEC §11), text measured in `drawWithCache`, semantics per segment,
goldens at 1× and 2× in both themes.

- **`LfAreaChart` (new)**, for one series over time (a month's spending by
  day, a year by month):
  - a smooth **monotone** cubic curve (Fritsch–Carlson). A plain spline
    overshoots: it would dip below zero or peak above the real maximum
    between two days, drawing money that was never spent.
  - under the line, the series colour fading from 24 % to 0 % opacity, top
    to bottom. That is the only gradient anywhere in the charts.
  - a baseline and nothing else: no grid. The latest point, or the one the
    user touches, gets a dot and a small value pill in Plex Mono.
  - x labels sparse (start, middle, end), y labels only on the pill.
- **Donut:** a thinner ring (12 dp at the default size), rounded ends, a 2 dp
  gap between slices, the total in the middle in Plex Mono. Slices keep their
  category colours: never a multi-hue gradient, because a colour here *means*
  a category.
- **Bars** (stacked, horizontal, mirrored): rounded ends on the free side, a
  quiet track (`chart.track`), and the grid kept to the few lines the axis
  labels need.
- **Progress and budget bars:** 8 dp tall, fully rounded. Budgets and goals use
  `action.primary`; over budget turns `status.warning`. Never a money colour,
  since a budget is a plan, not a movement of money.
- **Calendar heatmap and treemap:** the `control` corner on cells and tiles;
  otherwise unchanged.

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
| Settings | Becomes two screens (D4, Q8). **Profile** is the fourth tab: `LfTabPage` with `LfProfileHeader` and one grouped panel of `LfNavRow`s (Account, Settings, Export data, and Sign out of Google Drive once Drive exists). **Settings** is an `LfSettingsPage` index of `LfNavRow`s, each showing its current value: Notifications, Organise, Data & backup, Security & privacy ("Phone's lock"), Appearance ("Dark"), Currency & region ("INR"), Tools, About, Help. Each opens its own `LfSettingsPage`. | L | today's 19 rows on one list → an index plus one page per section |
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
| Settings' V2-3 rows: switches, inline choices, read-only Base currency | rows on their section's `LfSettingsPage`: `LfSwitchRow`, `LfNavRow` with the value, `LfSelectRow` on the choice page | S | three row kinds → the samples' three (navigation, select, switch) |
| Options (dialog, V2-3) | `LfDialog` with `LfSelectRow`s | — | |
| Payment apps (V2-3) | `LfSettingsPage` | L ("Reading the apps…"), E, S | |
| Add an app? (dialog, V2-3) | `LfDialog` Warning | — | |

### 5.5 Out of scope (needs the data layer or new features)

- Editing a saved entry (no update path exists for a committed `ledger_entry`;
  it needs a use case and a Law 1 review).
- Undoing a budget delete (it's a hard delete in the repository). This plan
  adds a confirmation instead.
- Catching storage failures *inside* repositories. This plan catches them in
  ViewModels and shows an error state; the repositories stay as they are.
- *Building* the screens in §5.6. This plan only decides how they will look,
  so the templates in step D1 are made with them in mind; each one is built
  by its own V2 phase.

### 5.6 Screens V2-PLAN and the reference samples add (not built yet)

§5.4 covers what exists. These are the screens `docs/V2-PLAN.md` and the
reference samples ask for that don't exist yet, mapped the same way, so that
step D1's templates and step C's components already fit them. The fourth
column names any part the design system doesn't have yet. That part is built
in `:core:designsystem` by the phase that first needs it (§4.4 item 3), not by
this redesign. The canvas draws each one.

**Where they live (Q8).** Profile is the fourth tab. It holds Account, Settings,
Export data and, once Drive exists, Sign out of Google Drive. Settings is an
index; **every section is its own `LfSettingsPage`**, as in the samples'
settings screen: Notifications, Organise, Data & sync, Receipts, Security &
privacy (with Data permissions under it), Appearance, Currency & region,
Tools, About, Help. The V2-7 tools sit on the Tools page.

| V2-PLAN | Screen | Template / component | States | New part needed |
|---|---|---|---|---|
| §3.1, `pfp.png` | Profile (tab) | `LfTabPage` + `LfProfileHeader` + grouped `LfNavRow`s | L | `LfProfileHeader`, `LfAvatar`, `LfNavRow`, `LfIconTile` |
| §3.1 | Account | `LfSettingsPage` | L, S | |
| §3.1 | Edit profile (name, photo) | `LfFormPage` | X, S | |
| §3.1, logout sheet | Sign out of Google Drive? | confirm `LfSheet` (No / Yes) | — | confirm sheet variant |
| settings sample | Settings index | `LfSettingsPage` of `LfNavRow`s with values | — | |
| §3.1 | Erase this install | `LfDialog` Warning, typed confirmation | X | `LfTypedConfirmField` (also §3.6 Restore) |
| §3.5 | Hide amounts on Home | a mode of the Home cards, tap to reveal | — | `LfMaskedAmount` (announced as "hidden", §9.6) |
| §3.5 | Data permissions | `LfSettingsPage`, one row per permission (why + Manage link) | L | |
| §3.4 | Bill / recurring reminders (rows) | `LfSettingsPage` switch rows + timing choice | — | |
| §3.4, §4.2 | Reminder notification | notification style (F4) | — | |
| §3.6 | Data & sync | `LfSettingsPage` (absorbs today's Data rows) | L, U, S | |
| §3.6 | Connect Google Drive | `LfFlowStep` (explain → system picker → confirm) | L, U (offline), X, S | |
| §3.6 | Drive status (last upload, sync) | `LfSettingsPage` section | L, U (offline), X | sync status row (`LfListRow` + status mark) |
| §3.6 | Import: choose file → preview → result | `LfFlowStep` ×3; result opens the Inbox | L, X, S | `LfTablePreview` (first rows, column per field) |
| §3.6, D8 | Approve N imported? | `LfDialog`, names the count (Law 1) | — | |
| §3.6 | Restore from Settings | today's Restore + typed-DELETE confirmation (§7.3) | L, X, S | `LfTypedConfirmField` |
| §3.7 | Receipts browser | `LfDetailPage` + period `LfSegmentedControl` | L, E, X, S | `LfThumbnailGrid` with multi-select |
| §3.7 | Receipt viewer | full-screen one-off (pinch zoom) | L, X | |
| §3.7 | Delete N receipts / Delete all | `LfDialog` Warning (count and size) | — | |
| §3.7 | Storage and auto-delete | `LfSettingsPage` rows | — | |
| §3.8 | About | `LfSettingsPage` | — | |
| §3.8 | What's new · Privacy policy · Terms | `LfDetailPage` holding text | L | `LfProse` (headings, paragraphs, lists) |
| §3.8 | Help & FAQ | `LfDetailPage` | E (no match) | `LfExpandableRow` |
| §3.8 | Report a bug (review before sending) | `LfFormPage` | S | |
| §3.8 | Open-source licences → one licence | `LfDetailPage` list → `LfProse` | L | |
| §5.1 | Merchant categories (Organise tab) | today's Organise tab + `TaxonomyCard` | L, E, X, S | |
| §5.2 | Merchants: Uncategorised (n) filter, multi-select, Set category | Organise tab + `LfChip` filter + selection bar | E, S | selection bar shared with Inbox and Bin |
| §5.3 | Merchant picker, browse by kind | today's picker dialog + `LfSectionHeader` groups | E | |
| §5.4 | Analytics "By merchant category" | `LfSection`: donut + ranked list, treemap toggle | L, E | |
| §5.4 | Merchant category → merchants → entries | `LfDetailPage` ×2 (`LfHorizontalBarChart`, paged `LfEntryRow`) | L, E | |
| §4.1 | Goals list · goal detail | `LfDetailPage` + `LfSection` | L, E, X | `LfProgressBar` (generalised from `LfBudgetBar`); a `progress` role, neutral colour (§4.4 item 4) |
| §4.1 | New / edit goal · add contribution | `LfFormPage` · `LfSheet` | X, S | |
| §4.2 | Recurring list · detail · suggestions to confirm | `LfDetailPage` + `LfSection` | L, E, X, S | |
| §4.2 | New / edit recurring item | `LfFormPage` (cadence choice, next date) | X, S | |
| §4.3 | Debts & loans (I owe / owed to me) · loan detail | `LfDetailPage` + `LfSection` | L, E, X | `LfSchedule` (repayment rows, paged) |
| §4.3 | New / edit loan · link a payment | `LfFormPage` · picker dialog | X, S | |
| §4.4 | Calculator ("use as amount") | `LfSheet` | — | `LfKeypad` |
| §4.4 | Currency converter | `LfFormPage` + "indicative" `LfNotice` | L, U (offline), X | |
| §4.4 | Split expense | `LfFormPage` | X, S | |
| §3.5, security sample | Security & privacy: choose Phone's lock or LedgerFlow PIN | `LfSettingsPage` + `LfSelectRow`s | — | |
| Q7, PIN samples | Set up PIN · confirm PIN · unlock with PIN | `LfFlowStep`, full screen | X (didn't match) | `LfPinPad`, `LfPinDots` |
| §6.8, language sample | Language (only if localisation is picked) | `LfSettingsPage` + `LfSelectRow`s | — | |
| Help | Help (also V2-PLAN §3.8) | as Help & FAQ above | | |

**Designed only: waits on V2-PLAN D3 (Q6).** Drawn on the canvas so the parts
exist when the decision is made; none is built while LedgerFlow has no
accounts.

| Sample | Screen | Template / component | States | New part needed |
|---|---|---|---|---|
| onboarding | Welcome carousel | `LfFlowStep` with pages, Sign up / Log in pair | — | `LfPagerDots`; artwork (Q9) |
| sign-up | Sign up (name, email, password, terms, "Continue with Google") | `LfFormPage` | X (field errors), S | `LfPasswordField`, `LfSocialButton` |
| login | Log in, Forgot password? | `LfFormPage` | X | |
| reset password | Reset password | `LfFormPage` | X, S | |
| verification | Enter your verification code (timer, "Send again") | `LfFlowStep` | X, S | `LfCodeInput` |

"Continue with Google" is the one part that does not wait on D3: it is how
Drive is linked (V2-PLAN D1, V2-4).

**V2-PLAN §6 (owner picks), mapped only once picked:** Search
(`LfDetailPage` with a search field in the header slot), transfers and refund
links (a row action plus a linked-pair row), accounts with balances
(`LfDetailPage`, one book per figure), tags (`LfChip` input), monthly
statement PDF (`LfFormPage`), home-screen widget (§4.4 item 5), Hindi UI
(longer labels and Devanagari: the font-scale-2.0 and BUG9 rules already
cover the layout side), general-user onboarding (`LfFlowStep`s), crash-log
export (a Settings row).

**What step D1 takes from this.** The templates need four slots that no
screen today uses all of: a search field in the header (Search, Help, the
merchant picker), a filter-chip band (Merchants' Uncategorised, Receipts'
period), a selection mode that swaps the action bar for "N selected"
(Merchants, Receipts, Inbox, Bin), and an offline state (§5.1) for the Drive
and converter screens.

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
| **B1** ✓ | Primitive layer (`theme/primitive/LfPrimitives.kt`, internal) and semantic roles (`LfColors` grouped as `surface`/`text`/`border`/`action`/`money`/`status`/`chart`; `LfTheme.type`, `LfTheme.space`, `LfTheme.borders`) with **today's exact values**; the 12 flat colour names, the `xs`…`xxl` steps and `LfTheme.typography` are deprecated aliases; the A2 Material mapping reads the roles. `LfTokenLayersTest` pins every value | **No: every golden byte-identical after re-recording** | stability report | `refactor: design tokens in three layers, same values` |
| **B2** ✓ | Component tokens, all `internal`: `component/LfComponentTokens.kt` and `chart/LfChartTokens.kt` in `:core:designsystem` (colours and text from the roles, a component's own spacing from `LfScale`), and `LfUiTokens.kt` in `:core:ui` (public roles only). Every component and chart in `:core:designsystem`, and the entry row, pickers, line-item editor and phrase screens in `:core:ui`, read their tokens; none reads a deprecated alias except two 24 dp sizes in `:core:ui` (entry-row swatch, phrase-entry gap) that wait for B3's `LfLayout`. `chart.track` now points at `surface.overlay`, the colour four of the five charts actually draw their tracks in; nothing else read it | **No: every golden byte-identical after re-recording** | stability report: no new unstable parameter, every restartable composable still skippable; `LfEntryRowLayoutTest`'s `inkDifferences` check passes | `refactor: components read component tokens` |
| **B3** ✓ | `LfTheme.shapes` (`LfShapeRoles`: `container` 16, `row` 24, `control` 16, `dialog` 24, `full` 50 %) and `LfLayout` (`minTouchTarget`, `actionMinWidth`, `segmentMinWidth`, `peekCardWidth`, `categoryDotSize`), **at today's values**; Material's `Shapes` read the roles. Today's 8 dp corner (fields, chips, segments, chart cells, Material's two small slots) has no role in Night Vault, so it is an internal `LfLegacySmallCorner` until F3. Every component and chart in `:core:designsystem` and `:core:ui` reads shapes and layout from these; `:core:ui`'s two 24 dp sizes moved to `LfLayout.categoryDotSize` and a new space role, `blockGap`. All of `LfSpacing` and `LfTheme.spacing` are now deprecated; feature screens keep the aliases until D3. `LfTokenLayersTest` pins the roles to the old values | **No: every golden byte-identical after re-recording** | stability report: no composable signature changed, every restartable composable still skippable | `refactor: shapes and layout constants leave LfSpacing` | `refactor: shapes and layout constants leave LfSpacing` |
| **C1** ✓ | `designTokenCheck` (root `build.gradle.kts`) scans `:app`, `:core:ui` and `:feature:*` main sources for the eight rule groups in §6.2, skipping comments, strings and imports, with `0.dp` allowed. `config/design-tokens-allowlist.txt` holds today's **38 violations in 15 files**, each with its reason and the step that removes it (C2: 8 picker and sheet widgets; D3: 11 corners, 17 sizes, 1 tint; F4: 1 stock icon). Counts must match exactly, so a new violation fails and a fixed one fails until its count is lowered: the list only shrinks. In `preMergeCheck` and, with A1's `goldenThemePairsCheck`, in CI's guards job | No | against a probe file holding one violation of every rule plus comment, string, import and `0.dp` decoys: all 8 caught, no decoy reported; a stale count, a malformed line and an unknown rule each rejected | `build: designTokenCheck rejects raw colours, sizes, shapes and widgets outside the theme` |
| **C2** ✓ | `component/LfOverlays.kt`: `LfDatePickerDialog`, `LfDateRangePickerDialog`, `LfBottomSheet`, `LfSnackbarHost`, plus `LfDatePicker` for Review's date step inside its `LfDialog`; their colours are component tokens. Buttons stay slots, so each screen keeps its own labels and emphasis. Entry, Budgets, Analytics and Review's pickers and the Add sheet moved to them, and `LfScaffold` hosts snackbars through `LfSnackbarHost`. Allowlist **38 → 30** (all 8 widget entries gone). Reviewing the new goldens found **BUG64**: A2's mapping had painted the date range's in-between band in the dialog's own colour, so a range showed only its two ends. Now `action.subtle`, with `Bug64_RangePickerShowsDaysBetweenTest` and a SPEC §8 row | Only BUG64's band; every one of the 272 existing goldens byte-identical | 8 wrapper goldens (whole screen, so the dialog and sheet windows show), both themes, reviewed; BUG64's test red without the fix; stability report: every wrapper skippable, `LfScaffold` unchanged | `refactor: pickers and sheets go through themed wrappers` |
| **D1** ✓ | Owner, 2026-10-25: **24 dp edge, 32 dp list end, the samples' top bar waits for F5.** `pattern/`: `LfTabPage`, `LfDetailPage`, `LfSettingsPage`, `LfFormPage`, `LfFlowStep`, `LfSheet`, `LfPanel`, `LfActionBar`, `LfSelectionBar`, all with today's look and the slots §5.6 asks for (an action band above the title (BUG17), search, filters, notices, `statusContent` for an empty, failed or offline page, a pinned bar or the selection bar); 26 goldens (1x, 2x, RTL, both themes). Every screen now uses `space.screenEdge` (Inbox, Review, the bin, Diagnostics, Payment apps, the Ledger list and the Back bands of Restore and Scan moved from 16 dp, and the Inbox title no longer pads twice) and `space.listEnd` (Home 16 → 32, Inbox and bin 16, Payment apps 24, Ledger and Organise 48). Every pinned bar is an `LfActionBar`: the edge across, `actionBarInset` (now 4 dp, what the bars used) vertically; Recovery's bar lost 40 dp of height, Restore's and Back up now's moved from 8 to 24 dp across. Screens adopt the page templates in D2 | Yes: 102 goldens in six modules, reviewed | the narrower content clipped "Move back" at font scale 2.0 by 3 dp (caught by the golden's BUG9 check): the Inbox row now gives its actions the width of their widest control when half is too little, and `LfButton`'s label reports its whole width as its minimum intrinsic width (no layout changed). The Ledger's longer amounts now stack below the name more often. **On the phone** (SM-S721B, `uiautomator dump` plus the navigation bar's 144 px inset, 2026-10-26), B3 → D1: Inbox title 40 → 24 dp, filter chips 16 → 24 dp, card text 32 → 40 dp; Back up now's button 8 → 24 dp from each side and 8 → 4 dp above the navigation bar, ending 12 px above its top, never under it | `feat(ui): screen templates; one edge margin everywhere` |
| **D2** ✓ | Screens adopt templates in five commits: the four tabs (`LfTabPage`; the Ledger's list pins its header) · Inbox (`LfDetailPage`, pinned), Review and Add entry (`LfFormPage`) · Payment apps (`LfDetailPage`), Notification capture and Back up now (`LfFormPage`) · onboarding, Recovery and the upgrade screens (`LfFlowStep`), Restore (`LfFormPage`, not `LfFlowStep`: it has a Back band and a pinned Restore bar, which is the form template's shape) · Export, Diagnostics and Scan (`LfFormPage`), Deleted entries and Organise (`LfDetailPage`, pinned; Organise's three lists are now one page's content), Budgets (`LfTabPage`). Left as they are, on purpose: the lock screen (an overlay, SPEC §7.6), Review's "entry missing" state and the shell itself | Yes: 98 goldens in five commits, reviewed pair by pair at 1x and 2x in both themes; the templates' own RTL goldens from D1 | Visible: empty states sit on the 24 dp edge; "Done" on Export, Deleted entries and Organise moves to the action band above the title (BUG17's placement), so "Deleted entries" fits on one line; Review's title 48 → 24 dp from the top; Payment apps' and Diagnostics' header gaps 4 → 8 dp; Notification capture's "Not now" on the 24 dp edge; Recovery gains a 24 dp top margin. Byte-identical: the tabs' content, Inbox, Add entry, Back up now, onboarding, Restore, the upgrade screens and Scan. The Settings tab is now a lazy list, so two of its tests moved to the tall 2x window with their assertions unchanged | `refactor(ui): Export, Deleted entries, Diagnostics, Scan, Budgets and Organise use the page templates` |
| **D3** | Shape and border roles applied consistently (cards, rows, fields, swipe tracks); hairline and dialog-height tokens; allowlist shrinks | Yes (corners) | | `fix(ui): one corner shape per role; hairlines from the theme` |
| **D4** | **Profile tab and Settings pages (Q8).** The fourth tab becomes Profile (`LfProfileHeader` showing "Set up your profile" until V2-PLAN §3.1 can store a name and photo, which needs schema and is out of scope here; `LfNavRow`s for Settings and Export data; Account and sign-out rows appear with their V2 phases). Settings becomes an index of `LfNavRow`s showing current values, and each of today's seven sections becomes its own `LfSettingsPage` with no row's behaviour changed. `SPEC.md` §9.3 and V2-PLAN D7 updated in the same commit | Yes (navigation) | a named test per route: every Settings row, Home's capture and backup banners, the budget-alert tap and `ledgerflow://` links still reach their page; Start screen choices unchanged; Back from a section returns to Settings, then Profile; device run, 2.0 and RTL | `feat(ui): Profile tab; every Settings section is its own page` |
| **E1** | `LfStateContent`, skeletons, `LfNotice`, `LfFeedbackHost`, field error line, progress step, `LfProgressBar`, and the confirm, options and filter variants of `LfSheet` (the Add sheet becomes the options variant), all with goldens in both themes | No (components only) | contrast test | `feat(ui): shared loading, empty, error and feedback components` |
| **E2–E6** | Screens adopt the state system in the D2 batches, closing §5.4's gaps. Each fix gets a Law 7 test named after it: **setup dead end first**, then Budgets' messages and delete confirmation, Entry's off-screen error, blank loading screens, the missing on-screen Back, the budget alert icon and tap | Yes | no-dead-end tests; device run of each fixed path | `fix(onboarding): BUGNN -- a failed setup offers Retry instead of "You're set up"` |
| **F1** | Docs only: ADR-0031 (Night Vault: its colours and fonts, plus the shapes and components taken from the reference samples, citing the folder), `SPEC.md` §9 rewritten to match, `V2-PLAN.md` §8 updated | No | | `docs: ADR-0031 visual direction; SPEC §9 follows` |
| **F2** | The chosen direction's primitives (and font file, if approved) replace today's; light and dark | **Yes: every golden** | contrast for every pair; review goldens screen by screen; device in both themes | `feat(ui): <direction> palette and type` |
| **F3** | Component token changes for the direction: §4.2's shape and size values; the samples' buttons, fields, checkbox, radio, switch and pill segmented tabs; the bottom bar with the raised + and labels; `LfIconTile` on entry rows in place of the 24 dp dot, drawn into the row's leaf so the row stays one `Layout` (`CLAUDE.md` §8), held to the old composition's structure by an `inkDifferences` test of everything but the tile | Yes | stability report; benchmarks on Ledger and Inbox (cool, interleaved); row height before/after from `uiautomator dump` | `feat(ui): Night Vault component styling` |
| **F4** | One icon family through `LfIcons`; notification icons generated from it | Yes | notification shade screenshots | `feat(ui): one icon set, notifications included` |
| **F5** | Screen-level composition the direction calls for (Home first, then the D2 batches) | Yes | per batch: goldens, 2.0, RTL, device, benchmarks for lists | `feat(home): Home in <direction>` |
| **F6** | Chart polish (§4.5): `LfAreaChart` (monotone curve, fading fill, value pill) with a unit test that the curve never leaves the data's range; rounded donut with gaps and a centre total; rounded bars; 8 dp progress bars. The Analytics screen picks which views use the area chart | Yes | goldens at 1× and 2×, both themes, reviewed; Analytics redraw test (`LfStackedBarChartRedrawTest` pattern) for the new chart; semantics per point | `feat(charts): area chart; rounded donut, bars and progress` |
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
