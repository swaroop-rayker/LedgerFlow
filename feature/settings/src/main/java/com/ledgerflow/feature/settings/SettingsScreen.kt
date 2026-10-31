package com.ledgerflow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.LfSwitchRow
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.DisplayLocale
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSettingsPage
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.model.DateOrder
import java.util.Locale

/**
 * One Settings page: a section's rows (SPEC.md §9.3; `docs/V2-PLAN.md` §3, D7).
 *
 * Settings was the fourth tab until design-system step D4, one list of seven
 * sections. Profile took the tab, Settings became an index of the sections
 * ([SettingsIndexScreen]) and each section is this page. **The rows did not
 * change**: same titles, sentences, dialogs and destinations, in the same
 * order, and [SettingsSections] is still the one place that says which row
 * lives where. The sections V2-PLAN §3 adds later (App lock PIN, Drive,
 * About...) are **absent until they are built**, with no "coming soon" rows
 * (owner, 2026-10-21) -- a row that cannot do anything is worse than none.
 *
 * Navigation callbacks rather than routes -- the shell owns the graph, so this
 * module never learns that `:feature:categories` exists.
 */
@Composable
public fun SettingsSectionScreen(
    page: SettingsPage,
    state: SettingsUiState,
    onBack: () -> Unit,
    onCategories: () -> Unit,
    onBudgets: () -> Unit,
    onExport: () -> Unit,
    onDeletedEntries: () -> Unit,
    onNotificationAccess: () -> Unit,
    onBackUp: () -> Unit,
    onDiagnostics: () -> Unit,
    onBudgetAlerts: () -> Unit,
    onPaymentApps: () -> Unit,
    onDataPermissions: () -> Unit,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsDialogs(state = state, onEvent = onEvent)

    // Where each navigating row goes; the shell owns the destinations. Rows
    // absent here are choices made in place, or a fact (the base currency,
    // D4) -- `SettingsSectionsTest` holds that every row is exactly one.
    val links: Map<SettingsItem, () -> Unit> = mapOf(
        SettingsItem.NotificationCapture to onNotificationAccess,
        SettingsItem.PaymentApps to onPaymentApps,
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
        SettingsItem.LockAfter to { onEvent(SettingsEvent.LockAfterRequested) },
        SettingsItem.DataPermissions to onDataPermissions,
        SettingsItem.Diagnostics to onDiagnostics,
    )
    val section = SettingsSections.single { it.page == page }

    LfSettingsPage(
        title = section.heading,
        modifier = modifier,
        onBack = onBack,
    ) {
        // One panel per page, with hairlines between its rows (step F5, owner
        // 2026-10-29), as the index and Profile are: a card per row read as
        // a stack of separate things.
        item(key = page.name, contentType = "section") {
            LfPanel {
                section.items.forEachIndexed { index, item ->
                    if (index > 0) LfDivider()
                    SettingsItemRow(item = item, state = state, onClick = links[item], onEvent = onEvent)
                }
            }
        }
    }
}

/**
 * The Settings pages, one per section, in [SettingsSections]' order.
 *
 * Public because the shell's route names one: `Destination.SettingsSection`
 * carries [name], and the shell reads it back with [settingsPageNamed].
 */
public enum class SettingsPage { Notifications, Organise, Data, Security, Appearance, Region, Tools }

/** The page a route's argument names, or null for a name no page has. */
public fun settingsPageNamed(name: String): SettingsPage? = SettingsPage.entries.firstOrNull { it.name == name }

/** Every row the Settings tab has. [SettingsSections] decides where each goes. */
internal enum class SettingsItem {
    NotificationCapture,
    PaymentApps,
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

    // Security & privacy (V2-3, V2-PLAN §3.5, SPEC §7.6).
    AppLock,
    LockAfter,
    BlockScreenshots,
    HideAmounts,
    DataPermissions,

    Diagnostics,
}

/** One page: its name and its rows, in drawing order. */
internal data class SettingsSection(val page: SettingsPage, val heading: String, val items: List<SettingsItem>)

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
    SettingsSection(
        SettingsPage.Notifications,
        "Notifications",
        listOf(SettingsItem.NotificationCapture, SettingsItem.PaymentApps, SettingsItem.BudgetAlerts),
    ),
    SettingsSection(SettingsPage.Organise, "Organise", listOf(SettingsItem.Categories, SettingsItem.Budgets)),
    SettingsSection(
        SettingsPage.Data,
        "Data",
        listOf(
            SettingsItem.BackUpNow,
            SettingsItem.Export,
            SettingsItem.DeletedEntries,
            SettingsItem.Receipts,
        ),
    ),
    SettingsSection(
        SettingsPage.Security,
        "Security & privacy",
        // What guards the app, then what the screen shows, then what the
        // app may reach: the permissions page last, as the one way out.
        listOf(
            SettingsItem.AppLock,
            SettingsItem.LockAfter,
            SettingsItem.BlockScreenshots,
            SettingsItem.HideAmounts,
            SettingsItem.DataPermissions,
        ),
    ),
    SettingsSection(
        SettingsPage.Appearance,
        "Appearance",
        listOf(SettingsItem.Theme, SettingsItem.Animations, SettingsItem.StartScreen),
    ),
    SettingsSection(
        SettingsPage.Region,
        "Currency & region",
        listOf(SettingsItem.BaseCurrency, SettingsItem.NumberGrouping, SettingsItem.DateOrder),
    ),
    SettingsSection(SettingsPage.Tools, "Tools", listOf(SettingsItem.Diagnostics)),
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
        SettingsItem.PaymentApps -> "Payment apps"
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
        SettingsItem.AppLock -> "App lock"
        SettingsItem.LockAfter -> "Lock after"
        SettingsItem.BlockScreenshots -> "Block screenshots"
        SettingsItem.HideAmounts -> "Hide amounts on Home"
        SettingsItem.DataPermissions -> "Data permissions"
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
internal fun subtitleFor(item: SettingsItem, state: SettingsUiState): String = SUBTITLES.getValue(item)(state)

/**
 * Each row's sentence, by row. A table rather than a `when`: one entry per row
 * is the whole of its logic, and `SettingsSectionsTest.everyRow_hasASentence`
 * fails if a row is added without one.
 */
private val SUBTITLES: Map<SettingsItem, (SettingsUiState) -> String> = mapOf(
    SettingsItem.NotificationCapture to { captureSubtitle(it) },
    SettingsItem.PaymentApps to { paymentAppsSubtitle(it.paymentApps) },
    SettingsItem.BudgetAlerts to { budgetAlertsSubtitle(it.budgetAlerts) },
    SettingsItem.Categories to { "Organise how spending is grouped" },
    SettingsItem.Budgets to { "Set a limit per category" },
    SettingsItem.BackUpNow to { backupSubtitle(it) },
    SettingsItem.Export to { "Save your data as an Excel workbook or CSV" },
    SettingsItem.DeletedEntries to { deletedSubtitle(it) },
    SettingsItem.Receipts to { receiptsSubtitle(it) },
    SettingsItem.AppLock to { appLockSubtitle(it) },
    SettingsItem.LockAfter to { lockAfterSubtitle(it.security) },
    SettingsItem.BlockScreenshots to { blockScreenshotsSubtitle(it.security.blockScreenshots) },
    SettingsItem.HideAmounts to { hideAmountsSubtitle(it.security.hideAmounts) },
    SettingsItem.DataPermissions to { "What LedgerFlow may use, why, and where to turn it off" },
    SettingsItem.Theme to { themeSubtitle(it.appearance.theme) },
    SettingsItem.Animations to { animationsSubtitle(it) },
    // The shell reads it once per launch (a live change would rebuild the
    // navigation graph under the user), so the sentence says when it applies.
    SettingsItem.StartScreen to { "Applies the next time the app opens." },
    SettingsItem.BaseCurrency to { baseCurrencySubtitle(it) },
    SettingsItem.NumberGrouping to { groupingSubtitle(it) },
    SettingsItem.DateOrder to { dateOrderSubtitle(it.region.dateOrder) },
    SettingsItem.Diagnostics to { "How automatic capture is working" },
)

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

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    PanelRow(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap),
            ) {
                Text(
                    text = title,
                    style = LfTheme.type.body,
                    color = LfTheme.colors.text.primary,
                )
                Text(
                    text = subtitle,
                    style = LfTheme.type.bodySmall,
                    color = LfTheme.colors.text.secondary,
                )
            }
            // It opens a page, as the index's rows do (step F5). Decoration:
            // the row's click already says so.
            Icon(
                imageVector = LfIcons.Chevron,
                contentDescription = null,
                tint = LfTheme.colors.text.muted,
                modifier = Modifier.padding(start = LfTheme.space.itemGap),
            )
        }
    }
}

/**
 * A row's frame inside the page's panel (step F5): the card's inset without
 * the card, so the rows' text sits where it did and the panel is the one
 * outline. Tappable only when it leads somewhere.
 */
@Composable
internal fun PanelRow(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LfLayout.minTouchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(LfTheme.space.cardInset),
    ) { content() }
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
    val toggle = toggleFor(item, state)
    when {
        onClick != null -> SettingsRow(title = item.title, subtitle = subtitleFor(item, state), onClick = onClick)
        choice != null ->
            ChoiceRow(title = item.title, choice = choice, subtitle = subtitleFor(item, state), onEvent = onEvent)
        toggle != null ->
            ToggleRow(title = item.title, toggle = toggle, subtitle = subtitleFor(item, state), onEvent = onEvent)
        else -> InfoRow(title = item.title, subtitle = subtitleFor(item, state))
    }
}

/**
 * A fact, not a control (the base currency, D4): the row's card without the
 * tap, so it reads as information rather than as a button that does nothing.
 */
@Composable
private fun InfoRow(title: String, subtitle: String) {
    PanelRow {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(text = title, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
            Text(text = subtitle, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
        }
    }
}

/** The dialogs a row can open: the receipts confirmation and the two pickers. */
@Composable
private fun SettingsDialogs(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    if (state.confirmingReceiptDelete) {
        DeleteReceiptsDialog(state = state, onEvent = onEvent)
    }

    if (state.choosingDateOrder) {
        OptionsDialog(
            title = "Date order",
            body = "How dates are written across LedgerFlow.",
            options = dateOrderOptions(),
            selectedIndex = state.region.dateOrder.ordinal,
            onPick = { onEvent(SettingsEvent.DateOrderPicked(DateOrder.entries[it])) },
            onClose = { onEvent(SettingsEvent.DateOrderDismissed) },
        )
    }
    if (state.choosingLockAfter) {
        OptionsDialog(
            title = "Lock after",
            body = "How long LedgerFlow may be away before it asks again.",
            options = LockTimeout.entries.map { it.label },
            selectedIndex = state.security.lockAfter.ordinal,
            onPick = { onEvent(SettingsEvent.LockAfterPicked(LockTimeout.entries[it])) },
            onClose = { onEvent(SettingsEvent.LockAfterDismissed) },
        )
    }
}

/**
 * A row's choice with too many options for the row itself (Date order, Lock
 * after): four would stack the in-place control into a column four buttons
 * tall. A pick applies and closes, the way a list of choices in Android's own
 * settings does. Stacked by the control itself, as four will be at any width a
 * dialog has.
 */
@Composable
private fun OptionsDialog(
    title: String,
    body: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    LfDialog(
        title = title,
        body = body,
        confirmText = "Close",
        dismissText = null,
        onConfirm = onClose,
        onDismiss = onClose,
        detail = { LfSegmentedControl(options = options, selectedIndex = selectedIndex, onSelect = onPick) },
    )
}

/**
 * A switch in the row's card (Security & privacy): the same card as every row,
 * so the screen keeps one shape; the sentence under the name says what the
 * switch does, or why it cannot be turned on.
 */
@Composable
private fun ToggleRow(title: String, toggle: SettingsToggle, subtitle: String, onEvent: (SettingsEvent) -> Unit) {
    PanelRow {
        LfSwitchRow(
            label = title,
            checked = toggle.checked,
            onCheckedChange = { onEvent(toggle.eventFor(it)) },
            supporting = subtitle,
            modifier = Modifier.defaultMinSize(minHeight = LfLayout.minTouchTarget),
            prominent = true,
        )
    }
}

/**
 * A choice made in place (Appearance): the same frame as [SettingsRow], so the
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
    PanelRow {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
            Text(
                text = title,
                style = LfTheme.type.body,
                color = LfTheme.colors.text.primary,
            )
            LfSegmentedControl(
                options = choice.options,
                selectedIndex = choice.selectedIndex,
                onSelect = { onEvent(choice.eventFor(it)) },
            )
            Text(
                text = subtitle,
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
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
        SettingsSectionScreen(
            page = SettingsPage.Data,
            state = SettingsUiState(
                deletedCount = 3,
                isLoaded = true,
                receipts = AttachmentUsage(count = 12, bytes = 3_400_000L),
            ),
            onBack = {},
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
            onBackUp = {},
            onDiagnostics = {},
            onBudgetAlerts = {},
            onPaymentApps = {},
            onDataPermissions = {},
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
        SettingsSectionScreen(
            page = SettingsPage.Data,
            state = SettingsUiState(deletedCount = 0, isLoaded = true),
            onBack = {},
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
            onBackUp = {},
            onDiagnostics = {},
            onBudgetAlerts = {},
            onPaymentApps = {},
            onDataPermissions = {},
            onEvent = {},
        )
    }
}
