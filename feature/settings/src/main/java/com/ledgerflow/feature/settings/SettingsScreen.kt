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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import java.text.DateFormat
import java.util.Date
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
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.confirmingReceiptDelete) {
        DeleteReceiptsDialog(state = state, onEvent = onEvent)
    }

    // The one row that does not navigate: it opens the confirmation, and the
    // confirmation is the only thing that deletes (ADR-0023).
    val requestReceiptDelete: () -> Unit = { onEvent(SettingsEvent.ReceiptDeleteRequested) }

    fun open(item: SettingsItem): () -> Unit = when (item) {
        SettingsItem.NotificationCapture -> onNotificationAccess
        SettingsItem.Categories -> onCategories
        SettingsItem.Budgets -> onBudgets
        SettingsItem.BackUpNow -> onBackUp
        SettingsItem.Export -> onExport
        SettingsItem.DeletedEntries -> onDeletedEntries
        SettingsItem.Receipts -> requestReceiptDelete
        SettingsItem.Diagnostics -> onDiagnostics
    }

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
                        SettingsRow(
                            title = item.title,
                            subtitle = subtitleFor(item, state),
                            onClick = open(item),
                        )
                    }
                }
            }
        }
    }
}

/** Every row the Settings tab has. [SettingsSections] decides where each goes. */
internal enum class SettingsItem {
    NotificationCapture,
    Categories,
    Budgets,
    BackUpNow,
    Export,
    DeletedEntries,
    Receipts,
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
 *   been dismissed, so it is always present and always enabled. V2-3's
 *   Notifications page (V2-PLAN §3.4) is where this section grows, budget alerts
 *   included; until then that switch stays in the budget editor (owner,
 *   2026-10-21).
 * - **Back up now directly above Export** (§16 Q23), because the two are easy
 *   to confuse and must not be: Export writes a plain spreadsheet anyone can
 *   read; Back up now writes the encrypted backup only the 24 words can open.
 * - **Diagnostics last**: it answers "is capture working?" in detail, which is
 *   a question for after the rows above, and it changes nothing.
 */
internal val SettingsSections: List<SettingsSection> = listOf(
    SettingsSection("Capture", listOf(SettingsItem.NotificationCapture)),
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
        SettingsItem.Categories -> "Categories & merchants"
        SettingsItem.Budgets -> "Budgets"
        SettingsItem.BackUpNow -> "Back up now"
        SettingsItem.Export -> "Export"
        SettingsItem.DeletedEntries -> "Deleted entries"
        SettingsItem.Receipts -> "Receipts"
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
    SettingsItem.Categories -> "Organise how spending is grouped"
    SettingsItem.Budgets -> "Set a limit per category"
    SettingsItem.BackUpNow -> backupSubtitle(state)
    SettingsItem.Export -> "Save your data as an Excel workbook or CSV"
    SettingsItem.DeletedEntries -> deletedSubtitle(state)
    SettingsItem.Receipts -> receiptsSubtitle(state)
    SettingsItem.Diagnostics -> "How automatic capture is working"
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
 * sentence before they lose the phone rather than after. Dates only, in the
 * device's locale; a backup's time of day does not change what to do next.
 */
internal fun backupSubtitle(state: SettingsUiState, locale: Locale = Locale.getDefault()): String {
    val last = state.lastBackupAt
        ?: return "No backup yet. Your data exists only on this phone."
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(last))
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
            onEvent = {},
        )
    }
}
