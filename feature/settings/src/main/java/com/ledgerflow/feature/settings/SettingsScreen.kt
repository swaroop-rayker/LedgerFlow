package com.ledgerflow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.DisplayLocale
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.bidiIsolated
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.model.CurrencyDisplay
import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import java.time.LocalDate
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import java.util.Locale

/**
 * The Settings tab (SPEC.md §9.3; `docs/V2-PLAN.md` §3, D7).
 *
 * It replaced the More tab in the bar at V2-2, and every row More had moved
 * here unchanged -- same title, same subtitle, same destination. What V2-2
 * added is the grouping ([SettingsSections]), and nothing else: the sections
 * V2-PLAN §3 adds later (Appearance, Currency & region, App lock, Drive,
 * About...) are **absent until they are built**, with no "coming soon" rows
 * (owner, 2026-10-21) -- a row that cannot do anything is worse than none, the
 * rule the centre action's dial has always followed.
 *
 * Navigation callbacks rather than routes -- the shell owns the graph, so this
 * module never learns that `:feature:categories` exists.
 */
@Composable
public fun SettingsScreen(
    state: SettingsUiState,
    onCategories: () -> Unit,
    onBudgets: () -> Unit,
    onExport: () -> Unit,
    onDeletedEntries: () -> Unit,
    onNotificationAccess: () -> Unit,
    onBackUp: () -> Unit,
    onDiagnostics: () -> Unit,
    onBudgetAlerts: () -> Unit,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.confirmingReceiptDelete) {
        DeleteReceiptsDialog(state = state, onEvent = onEvent)
    }

    if (state.choosingDateOrder) {
        DateOrderDialog(selected = state.region.dateOrder, onEvent = onEvent)
    }

    // Where each navigating row goes; the shell owns the destinations. Rows
    // absent here are choices made in place, or a fact (the base currency,
    // D4) -- `SettingsSectionsTest` holds that every row is exactly one.
    val links: Map<SettingsItem, () -> Unit> = mapOf(
        SettingsItem.NotificationCapture to onNotificationAccess,
        SettingsItem.BudgetAlerts to onBudgetAlerts,
        SettingsItem.Categories to onCategories,
        SettingsItem.Budgets to onBudgets,
        SettingsItem.BackUpNow to onBackUp,
        SettingsItem.Export to onExport,
        SettingsItem.DeletedEntries to onDeletedEntries,
        // The one row that does not navigate: it opens the confirmation, and
        // the confirmation is the only thing that deletes (ADR-0023).
        SettingsItem.Receipts to { onEvent(SettingsEvent.ReceiptDeleteRequested) },
        // Four options do not fit a row's control (they would stack into a
        // column four buttons tall), so this one opens a short picker.
        SettingsItem.DateOrder to { onEvent(SettingsEvent.DateOrderRequested) },
        SettingsItem.Diagnostics to onDiagnostics,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        LfScreenTitle(title = "Settings")
        Column(
            modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
        ) {
            SettingsSections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
                    SectionHeading(section.heading)
                    section.items.forEach { item ->
                        SettingsItemRow(item = item, state = state, onClick = links[item], onEvent = onEvent)
                    }
                }
            }
        }
    }
}

/** Every row the Settings tab has. [SettingsSections] decides where each goes. */
internal enum class SettingsItem {
    NotificationCapture,
    BudgetAlerts,
    Categories,
    Budgets,
    BackUpNow,
    Export,
    DeletedEntries,
    Receipts,

    // Appearance (V2-3, V2-PLAN §3.2): choices made in place, not ways out.
    Theme,
    Animations,
    StartScreen,

    // Currency & region (V2-3, V2-PLAN §3.3, D4).
    BaseCurrency,
    NumberGrouping,
    DateOrder,

    Diagnostics,
}

/** One heading and the rows under it, in drawing order. */
internal data class SettingsSection(val heading: String, val items: List<SettingsItem>)

/**
 * The tab's layout, as data, so `SettingsSectionsTest` can hold it: a row that
 * drops out of this list disappears from the app with nothing else failing.
 *
 * The split is the owner's (2026-10-21): every row More had lives here, and
 * Diagnostics is the one tool. A **Tools section rather than a Tools screen**:
 * a screen of one row is a tap that shows nothing new. V2-7's tools (goals,
 * recurring, debts, utilities) join this section, and earn a screen of their
 * own once there are enough of them to need one.
 *
 * Order inside each section is the order More had, which each row's position
 * was chosen for:
 *
 * - **Notification capture first**, because it is the only row that can be
 *   *wrong* -- the others open something that always works. It is also the
 *   standing route back to §5.2's explainer once the first-run presentation has
 *   been dismissed, so it is always present and always enabled. **V2-3 renamed
 *   the section Notifications** (V2-PLAN §3.4 "takes over Capture") and added
 *   **Budget alerts** under it: what LedgerFlow reads first, what it sends
 *   second. There was never an in-app budget-alerts switch to move (V2-2's
 *   note assumed one); Android's channel is the switch, and the row reports it.
 * - **Back up now directly above Export** (§16 Q23), because the two are easy
 *   to confuse and must not be: Export writes a plain spreadsheet anyone can
 *   read; Back up now writes the encrypted backup only the 24 words can open.
 * - **Appearance after Data** (V2-3, 2026-10-24): how the app looks is set
 *   once and rarely revisited, so it sits below the sections people come back
 *   to, and above Tools, which stays last. **Currency & region** follows it,
 *   for the same reason and in V2-PLAN §3's order.
 * - **Diagnostics last**: it answers "is capture working?" in detail, which is
 *   a question for after the rows above, and it changes nothing.
 */
internal val SettingsSections: List<SettingsSection> = listOf(
    SettingsSection("Notifications", listOf(SettingsItem.NotificationCapture, SettingsItem.BudgetAlerts)),
    SettingsSection("Organise", listOf(SettingsItem.Categories, SettingsItem.Budgets)),
    SettingsSection(
        "Data",
        listOf(
            SettingsItem.BackUpNow,
            SettingsItem.Export,
            SettingsItem.DeletedEntries,
            SettingsItem.Receipts,
        ),
    ),
    SettingsSection(
        "Appearance",
        listOf(SettingsItem.Theme, SettingsItem.Animations, SettingsItem.StartScreen),
    ),
    SettingsSection(
        "Currency & region",
        listOf(SettingsItem.BaseCurrency, SettingsItem.NumberGrouping, SettingsItem.DateOrder),
    ),
    SettingsSection("Tools", listOf(SettingsItem.Diagnostics)),
)

/**
 * Each row's name, unchanged by the move.
 *
 * **"Deleted entries", not "Erase deleted entries".** The row used to perform
 * the erase itself, so it was named for the verb; it now opens the bin, and a
 * row that navigates takes a noun. Naming a destination after the most
 * destructive thing you can do inside it is how you get people afraid to open
 * it. Not "Recently deleted" either, for all that the platform leans that way:
 * nothing here expires on a timer, and the word would promise a sweep the app
 * does not perform.
 */
internal val SettingsItem.title: String
    get() = when (this) {
        SettingsItem.NotificationCapture -> "Notification capture"
        SettingsItem.BudgetAlerts -> "Budget alerts"
        SettingsItem.Categories -> "Categories & merchants"
        SettingsItem.Budgets -> "Budgets"
        SettingsItem.BackUpNow -> "Back up now"
        SettingsItem.Export -> "Export"
        SettingsItem.DeletedEntries -> "Deleted entries"
        SettingsItem.Receipts -> "Receipts"
        SettingsItem.Theme -> "Theme"
        SettingsItem.Animations -> "Animations"
        SettingsItem.StartScreen -> "Start screen"
        SettingsItem.BaseCurrency -> "Base currency"
        SettingsItem.NumberGrouping -> "Number grouping"
        SettingsItem.DateOrder -> "Date order"
        SettingsItem.Diagnostics -> "Diagnostics"
    }

/**
 * What each row says about itself.
 *
 * The bin's and the receipts' rows are always present and always enabled, even
 * at zero. The bin's was hidden when there was nothing to erase, and that was
 * reported as the feature being missing -- a control that exists only sometimes
 * is indistinguishable from one that was never built. ADR-0023 declines a timed
 * purge of receipts and makes their growth visible instead, so that row is kept
 * for the same reason.
 */
internal fun subtitleFor(item: SettingsItem, state: SettingsUiState): String = when (item) {
    SettingsItem.NotificationCapture -> captureSubtitle(state)
    SettingsItem.BudgetAlerts -> budgetAlertsSubtitle(state.budgetAlerts)
    SettingsItem.BackUpNow -> backupSubtitle(state)
    SettingsItem.DeletedEntries -> deletedSubtitle(state)
    SettingsItem.Receipts -> receiptsSubtitle(state)
    SettingsItem.Theme -> themeSubtitle(state.appearance.theme)
    SettingsItem.Animations -> animationsSubtitle(state)
    SettingsItem.BaseCurrency -> baseCurrencySubtitle(state)
    SettingsItem.NumberGrouping -> groupingSubtitle(state)
    SettingsItem.DateOrder -> dateOrderSubtitle(state.region.dateOrder)
    SettingsItem.Categories,
    SettingsItem.Budgets,
    SettingsItem.Export,
    SettingsItem.StartScreen,
    SettingsItem.Diagnostics,
    -> FIXED_SUBTITLES.getValue(item)
}

/** The rows whose sentence never changes with state. */
private val FIXED_SUBTITLES: Map<SettingsItem, String> = mapOf(
    SettingsItem.Categories to "Organise how spending is grouped",
    SettingsItem.Budgets to "Set a limit per category",
    SettingsItem.Export to "Save your data as an Excel workbook or CSV",
    // The shell reads it once per launch (a live change would rebuild the
    // navigation graph under the user), so the sentence says when it applies.
    SettingsItem.StartScreen to "Applies the next time the app opens.",
    SettingsItem.Diagnostics to "How automatic capture is working",
)

/**
 * What the budget-alerts row says (V2-PLAN §3.4): the channel's state, and
 * what turning it back on takes. Null before the first read on resume.
 */
internal fun budgetAlertsSubtitle(state: AlertChannelState?): String = when (state) {
    null -> "When a budget nears its limit, or is on course to go over."
    AlertChannelState.ON -> "On. When a budget nears its limit, or is on course to go over."
    AlertChannelState.CHANNEL_OFF -> "Off in Android settings. Tap to turn them on."
    AlertChannelState.APP_OFF -> "LedgerFlow's notifications are off. Tap to turn them on."
}

/**
 * D4: shown, never changed. Every amount in the vault is minor units of this
 * currency, so changing it would relabel every figure without converting any.
 * The sentence says so, rather than leaving a row that looks broken.
 */
internal fun baseCurrencySubtitle(state: SettingsUiState): String {
    val code = state.baseCurrency ?: return "Chosen when LedgerFlow was set up."
    // Isolated, so the dirham's right-to-left symbol cannot turn the line
    // around (BUG61).
    val symbol = bidiIsolated(CurrencyDisplay.symbolOf(code))
    return "$symbol ${CurrencyDisplay.nameOf(code)} ($code). Every amount is kept in it, so it can't change."
}

/** A long amount in the base currency, as the chosen grouping writes it. */
internal fun groupingSubtitle(state: SettingsUiState): String {
    val code = state.baseCurrency ?: DEFAULT_CURRENCY
    val sample = MoneyFormat.symbolised(
        GROUPING_SAMPLE_MAJOR * POWERS_OF_TEN[CurrencyExponent.of(code)],
        code,
        grouping = state.region.grouping,
    )
    return when (state.region.grouping) {
        NumberGrouping.BY_CURRENCY -> "$sample, as ${CurrencyDisplay.spokenUnitOf(code)} are written."
        NumberGrouping.LAKH -> "$sample, in lakh and crore."
        NumberGrouping.WESTERN -> "$sample, in thousands and millions."
    }
}

/** One date, in each order: a day past the 12th, so the order cannot be misread. */
internal fun dateOrderSubtitle(order: DateOrder): String {
    val sample = DisplayDates.medium(DATE_SAMPLE, order = order)
    return when (order) {
        DateOrder.AUTO -> "Your region's style: $sample"
        DateOrder.DAY_MONTH_YEAR -> "Day first: $sample"
        DateOrder.MONTH_DAY_YEAR -> "Month first: $sample"
        DateOrder.YEAR_MONTH_DAY -> "Year first: $sample"
    }
}

/** The picker's options: each order named by how it writes the sample date. */
internal fun dateOrderOptions(): List<String> = DateOrder.entries.map { order ->
    when (order) {
        DateOrder.AUTO -> "Region style"
        DateOrder.DAY_MONTH_YEAR, DateOrder.MONTH_DAY_YEAR, DateOrder.YEAR_MONTH_DAY ->
            DisplayDates.medium(DATE_SAMPLE, order = order)
    }
}

private val DATE_SAMPLE: LocalDate = LocalDate.of(2026, 10, 24)
private const val GROUPING_SAMPLE_MAJOR = 1_234_567L
private const val DEFAULT_CURRENCY = "INR"

/** Indexed by ISO-4217 exponent (0, 2 and 3 occur), as `MoneyFormat` keeps it. */
private val POWERS_OF_TEN = longArrayOf(1L, 10L, 100L, 1_000L)

/**
 * One in-place choice: its options, which is selected, and the event a pick
 * sends. Null for a row that navigates.
 */
@Immutable
internal class SettingsChoice(
    val options: List<String>,
    val selectedIndex: Int,
    val eventFor: (Int) -> SettingsEvent,
)

/** The choice an Appearance row offers (V2-PLAN §3.2), or null for any other row. */
internal fun choiceFor(item: SettingsItem, state: SettingsUiState): SettingsChoice? = when (item) {
    SettingsItem.Theme -> SettingsChoice(
        options = ThemePreference.entries.map { it.label },
        selectedIndex = state.appearance.theme.ordinal,
        eventFor = { SettingsEvent.ThemePicked(ThemePreference.entries[it]) },
    )

    SettingsItem.Animations -> SettingsChoice(
        options = AnimationPreference.entries.map { it.label },
        selectedIndex = state.appearance.animations.ordinal,
        eventFor = { SettingsEvent.AnimationsPicked(AnimationPreference.entries[it]) },
    )

    SettingsItem.StartScreen -> SettingsChoice(
        options = StartScreen.entries.map { it.label },
        selectedIndex = state.appearance.startScreen.ordinal,
        eventFor = { SettingsEvent.StartScreenPicked(StartScreen.entries[it]) },
    )

    SettingsItem.NumberGrouping -> SettingsChoice(
        options = NumberGrouping.entries.map { it.label },
        selectedIndex = state.region.grouping.ordinal,
        eventFor = { SettingsEvent.GroupingPicked(NumberGrouping.entries[it]) },
    )

    SettingsItem.BaseCurrency,
    SettingsItem.DateOrder,
    SettingsItem.NotificationCapture,
    SettingsItem.BudgetAlerts,
    SettingsItem.Categories,
    SettingsItem.Budgets,
    SettingsItem.BackUpNow,
    SettingsItem.Export,
    SettingsItem.DeletedEntries,
    SettingsItem.Receipts,
    SettingsItem.Diagnostics,
    -> null
}

private val ThemePreference.label: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "System"
        ThemePreference.LIGHT -> "Light"
        ThemePreference.DARK -> "Dark"
    }

private val AnimationPreference.label: String
    get() = when (this) {
        AnimationPreference.FULL -> "Full"
        AnimationPreference.REDUCED -> "Reduced"
        AnimationPreference.OFF -> "Off"
    }

private val NumberGrouping.label: String
    get() = when (this) {
        NumberGrouping.BY_CURRENCY -> "Currency"
        NumberGrouping.LAKH -> "Lakh"
        NumberGrouping.WESTERN -> "Western"
    }

/** "Home", as the bottom bar names it -- not "Dashboard". */
private val StartScreen.label: String
    get() = when (this) {
        StartScreen.HOME -> "Home"
        StartScreen.LEDGER -> "Ledger"
        StartScreen.ANALYTICS -> "Analytics"
    }

/** One line each at 1.0: the row is a choice to scan, not a paragraph. */
internal fun themeSubtitle(theme: ThemePreference): String = when (theme) {
    ThemePreference.SYSTEM -> "Light or dark, as your phone is set."
    ThemePreference.LIGHT -> "Always light."
    ThemePreference.DARK -> "Always dark."
}

/**
 * What the chosen motion means, and -- the case that would otherwise look like
 * a broken setting -- why nothing moves on a phone that has removed animations,
 * whichever option is picked (SPEC §9.5: the phone's switch always wins).
 */
internal fun animationsSubtitle(state: SettingsUiState): String = when {
    !state.phoneAnimationsOn -> "Your phone has animations removed, so nothing here moves either."
    else -> when (state.appearance.animations) {
        AnimationPreference.FULL -> "Screens slide and fade."
        AnimationPreference.REDUCED -> "Screens fade. Nothing slides."
        AnimationPreference.OFF -> "Nothing moves."
    }
}

/**
 * What the bin row says about itself.
 *
 * The zero case has to explain what the row is *for*, not merely that it is
 * empty: that is the state a user reading Settings to learn what the app can do
 * will normally find it in.
 */
internal fun deletedSubtitle(state: SettingsUiState): String = when {
    !state.isLoaded -> "Restore or permanently erase deleted entries"
    state.deletedCount == 0 -> "Nothing deleted. Entries you delete are kept here."
    state.deletedCount == 1 -> "1 entry kept here. Restore it or erase it for good."
    else -> "${state.deletedCount} entries kept here. Restore them or erase them for good."
}

/**
 * The mis-tap guard on deleting every receipt image (ADR-0023).
 *
 * Three things it has to do, each decided by the bin's erase first:
 *
 * - **Name the count**, because a dialog that only asks "are you sure?" is one
 *   people learn to tap through.
 * - **Say it cannot be undone**, in those words.
 * - **Say plainly that nothing else holds a copy.** It used to say "Export
 *   first if you want to keep them" — but the export is CSV, and the
 *   attachment CSV is metadata only (ADR-0023), so following that advice kept
 *   no photograph at all. A sentence promising a durability the app does not
 *   have is exactly what the purge dialog and the pre-migration snapshot are
 *   forbidden to say (ADR-0019). Since "Back up now" (§16 Q23) copies the
 *   photos into the backup folder, the sentence says exactly that much: this
 *   phone keeps no other copy, and photos included in a backup stay in that
 *   folder. **It does not say they can be restored.** Revisited when restore
 *   shipped (ADR-0026) and kept: restore rebuilds a whole vault on a fresh
 *   install, so it is no way to get a deleted photo back on this phone, and
 *   offering it as one would be the durability promise this rule forbids.
 *
 * And one thing specific to this dialog: it says the **entries survive**.
 * Deleting a photograph is not deleting a purchase, and a user who thought
 * otherwise would never tap it — or would tap it and be horrified.
 */
@Composable
private fun DeleteReceiptsDialog(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    LfDialog(
        title = deleteReceiptsTitle(state),
        body = deleteReceiptsBody(state),
        confirmText = "Delete",
        // Warning emphasis also stops an outside tap from standing in for an
        // answer, which on an irreversible action would defeat the point.
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(SettingsEvent.ReceiptDeleteConfirmed) },
        onDismiss = { onEvent(SettingsEvent.ReceiptDeleteDismissed) },
    )
}

/**
 * What the backup row says about itself (§16 Q23).
 *
 * **The never-backed-up case says what that means**, not merely "never": the
 * app keeps no copy anywhere else, and a user reading Settings is owed that
 * sentence before they lose the phone rather than after. Dates only, in English
 * in the phone's region (`DisplayLocale`, BUG61); a backup's time of day does
 * not change what to do next.
 */
internal fun backupSubtitle(state: SettingsUiState, locale: Locale = DisplayLocale.current()): String {
    val last = state.lastBackupAt
        ?: return "No backup yet. Your data exists only on this phone."
    val date = DisplayDates.medium(last, locale = locale)
    // With nightly backups on, "asks for your 24 words" would be the wrong
    // expectation to set: the phone backs up by itself, and the words are for
    // the backup the user asks for on top of that (ADR-0027).
    return if (state.nightlyBackupsEnabled) {
        "Last backup $date. Backs up nightly on its own."
    } else {
        "Last backup $date. Asks for your 24 words."
    }
}

/** The delete dialog's title, with the count named (see [DeleteReceiptsDialog]). */
internal fun deleteReceiptsTitle(state: SettingsUiState): String {
    val count = state.receipts.count
    return if (count == 1) "Delete 1 receipt image?" else "Delete $count receipt images?"
}

/**
 * The delete dialog's body. Separate from the composable so the promise it
 * makes — or refuses to make — is pinned by a test rather than by review.
 */
internal fun deleteReceiptsBody(state: SettingsUiState): String =
    "This frees ${formatSize(state.receipts.bytes)} and cannot be undone. " +
        "Your entries and their amounts are untouched — only the photographs go. " +
        "This phone keeps no other copy. Photos included in a backup made with " +
        "“Back up now” stay in that backup folder."

/**
 * What the receipts row says about itself (ADR-0023).
 *
 * The number is the point: the ADR's answer to unbounded growth is to show it
 * rather than to delete on a timer, so a subtitle that omitted the size would
 * be the decision without its substance.
 *
 * The zero case explains what receipts *are* rather than merely saying none —
 * that is the state a user reading Settings to learn what the app does will
 * normally find, and "0 images" teaches them nothing.
 */
internal fun receiptsSubtitle(state: SettingsUiState): String = when {
    !state.isLoaded -> "Images kept with your scanned receipts"
    state.receipts.count == 0 ->
        "No images yet. Receipts you scan are kept here, encrypted."
    state.receipts.count == 1 -> "1 image, ${formatSize(state.receipts.bytes)}. Tap to delete."
    else ->
        "${state.receipts.count} images, ${formatSize(state.receipts.bytes)}. Tap to delete."
}

/**
 * A size a person reads, rounded down to whole units.
 *
 * Binary units against decimal labels is the usual muddle; this uses 1000 and
 * says kB/MB, matching what Android's own storage screens show, so the two do
 * not disagree in front of the user.
 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= BYTES_PER_MB -> "${bytes / BYTES_PER_MB} MB"
    bytes >= BYTES_PER_KB -> "${bytes / BYTES_PER_KB} kB"
    else -> "$bytes bytes"
}

/** Decimal, not binary — see [formatSize]. */
private const val BYTES_PER_KB = 1_000L
private const val BYTES_PER_MB = 1_000_000L

/**
 * What the notification row says about itself (SPEC.md §5.2).
 *
 * Each state names the *consequence* rather than the mechanism, because the
 * mechanism is what the screen behind the row is for. "Off" alone would not tell
 * the user that payments are being missed, which is the only fact that makes the
 * row worth tapping.
 *
 * [NotificationCaptureHealth.RECONNECTING] is the pre-poll value as well as a
 * real transient state, so its sentence has to be true of both: it says what the
 * row is for and claims nothing about the current grant.
 */
internal fun captureSubtitle(state: SettingsUiState): String = when (state.captureHealth) {
    NotificationCaptureHealth.CONNECTED -> "On. Payment notifications reach your Inbox."
    NotificationCaptureHealth.NOT_GRANTED ->
        "Off. Payments that only send a notification are being missed."

    NotificationCaptureHealth.DEAD ->
        "Stopped. Android has not reconnected the listener — tap to check."

    NotificationCaptureHealth.RECONNECTING ->
        "Read payment notifications, and what LedgerFlow does with them."

    // Unreachable for notifications -- both flavours ship the listener and every
    // supported device can host one -- but an enum `when` may not have an `else`
    // (CLAUDE.md §5), and a sentence is cheaper than a lie.
    NotificationCaptureHealth.UNAVAILABLE ->
        "Not available on this device."
}

/**
 * A section's name: a plain line over its rows, not a card of its own, so the
 * screen keeps one shape (the rows' card). Marked as a heading so TalkBack can
 * move between sections (§9.6).
 */
@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = LfTheme.typography.label,
        color = LfTheme.colors.textSecondary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    LfCard(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

/** One row: a way somewhere when [onClick] is given, otherwise a choice made in place. */
@Composable
private fun SettingsItemRow(
    item: SettingsItem,
    state: SettingsUiState,
    onClick: (() -> Unit)?,
    onEvent: (SettingsEvent) -> Unit,
) {
    val choice = choiceFor(item, state)
    when {
        onClick != null -> SettingsRow(title = item.title, subtitle = subtitleFor(item, state), onClick = onClick)
        choice != null ->
            ChoiceRow(title = item.title, choice = choice, subtitle = subtitleFor(item, state), onEvent = onEvent)
        else -> InfoRow(title = item.title, subtitle = subtitleFor(item, state))
    }
}

/**
 * A fact, not a control (the base currency, D4): the row's card without the
 * tap, so it reads as information rather than as a button that does nothing.
 */
@Composable
private fun InfoRow(title: String, subtitle: String) {
    LfCard(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = LfTheme.spacing.minTouchTarget)) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(text = title, style = LfTheme.typography.bodyL, color = LfTheme.colors.textPrimary)
            Text(text = subtitle, style = LfTheme.typography.bodyM, color = LfTheme.colors.textSecondary)
        }
    }
}

/**
 * Settings > Currency & region > Date order. Four options, each named by how
 * it writes one date; a pick applies and closes, the way a list of choices in
 * Android's own settings does. Stacked by the control itself, as four will be
 * at any width a dialog has.
 */
@Composable
private fun DateOrderDialog(selected: DateOrder, onEvent: (SettingsEvent) -> Unit) {
    LfDialog(
        title = "Date order",
        body = "How dates are written across LedgerFlow.",
        confirmText = "Close",
        dismissText = null,
        onConfirm = { onEvent(SettingsEvent.DateOrderDismissed) },
        onDismiss = { onEvent(SettingsEvent.DateOrderDismissed) },
        detail = {
            LfSegmentedControl(
                options = dateOrderOptions(),
                selectedIndex = selected.ordinal,
                onSelect = { onEvent(SettingsEvent.DateOrderPicked(DateOrder.entries[it])) },
            )
        },
    )
}

/**
 * A choice made in place (Appearance): the same card as [SettingsRow], so the
 * screen keeps one shape, with the options between the title and the sentence
 * that says what the current one means. It applies on tap; there is nothing to
 * confirm, and the theme visibly changing is the confirmation.
 *
 * The control stacks its options at large font scales rather than breaking a
 * label (BUG9, `LfSegmentedControl`).
 */
@Composable
private fun ChoiceRow(
    title: String,
    choice: SettingsChoice,
    subtitle: String,
    onEvent: (SettingsEvent) -> Unit,
) {
    LfCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs)) {
            Text(
                text = title,
                style = LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
            )
            LfSegmentedControl(
                options = choice.options,
                selectedIndex = choice.selectedIndex,
                onSelect = { onEvent(choice.eventFor(it)) },
            )
            Text(
                text = subtitle,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun SettingsPreview() {
    LfTheme {
        SettingsScreen(
            state = SettingsUiState(
                deletedCount = 3,
                isLoaded = true,
                receipts = AttachmentUsage(count = 12, bytes = 3_400_000L),
            ),
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
            onBackUp = {},
            onDiagnostics = {},
            onBudgetAlerts = {},
            onEvent = {},
        )
    }
}

/** An empty bin — the state most users see most of the time. */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun SettingsEmptyBinPreview() {
    LfTheme {
        SettingsScreen(
            state = SettingsUiState(deletedCount = 0, isLoaded = true),
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
            onBackUp = {},
            onDiagnostics = {},
            onBudgetAlerts = {},
            onEvent = {},
        )
    }
}
