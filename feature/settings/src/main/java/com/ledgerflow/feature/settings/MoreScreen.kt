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
 * The "More" tab (SPEC.md §9.3): everything that is not one of the three main
 * surfaces.
 *
 * Navigation callbacks rather than routes -- the shell owns the graph, so this
 * module never learns that `:feature:categories` exists.
 */
@Composable
public fun MoreScreen(
    state: MoreUiState,
    onCategories: () -> Unit,
    onBudgets: () -> Unit,
    onExport: () -> Unit,
    onDeletedEntries: () -> Unit,
    onNotificationAccess: () -> Unit,
    onBackUp: () -> Unit,
    onEvent: (MoreEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.confirmingReceiptDelete) {
        DeleteReceiptsDialog(state = state, onEvent = onEvent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
    ) {
        LfScreenTitle(title = "More")
        Column(
            modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            // §5.2. First, because it is the only row that can be *wrong* --
            // the others open something that always works. It is also the
            // standing route back to the explainer once the first-run
            // presentation has been dismissed, so it is always present and
            // always enabled, for the reason the bin row below records.
            MoreRow(
                title = "Notification capture",
                subtitle = captureSubtitle(state),
                onClick = onNotificationAccess,
            )
            MoreRow(
                title = "Categories & merchants",
                subtitle = "Organise how spending is grouped",
                onClick = onCategories,
            )
            MoreRow(
                title = "Budgets",
                subtitle = "Set a limit per category",
                onClick = onBudgets,
            )
            // §16 Q23. Directly above Export, because the two are easy to
            // confuse and must not be: Export writes plain CSV anyone can read;
            // this writes the encrypted backup only the 24 words can open.
            MoreRow(
                title = "Back up now",
                subtitle = backupSubtitle(state),
                onClick = onBackUp,
            )
            MoreRow(
                title = "Export",
                subtitle = "Save your data as CSV",
                onClick = onExport,
            )
            // **"Deleted entries", not "Erase deleted entries".** The row used
            // to perform the erase itself, so it was named for the verb; it now
            // opens the bin, and a row that navigates takes a noun. Naming a
            // destination after the most destructive thing you can do inside it
            // is how you get people afraid to open it.
            //
            // Not "Recently deleted" either, for all that the platform leans
            // that way: nothing here expires on a timer, and the word would
            // promise a sweep the app does not perform.
            //
            // Always present and always enabled, even at zero. It was hidden
            // when there was nothing to erase, and that was reported as the
            // feature being missing -- a control that exists only sometimes is
            // indistinguishable from one that was never built.
            MoreRow(
                title = "Deleted entries",
                subtitle = deletedSubtitle(state),
                onClick = onDeletedEntries,
            )
            // ADR-0023 declines a timed purge and makes growth visible
            // instead. Always present and always enabled, for the same reason
            // the bin's row is: a control that appears only when there is
            // something in it is indistinguishable from one that was never
            // built.
            MoreRow(
                title = "Receipts",
                subtitle = receiptsSubtitle(state),
                onClick = { onEvent(MoreEvent.ReceiptDeleteRequested) },
            )
        }
    }
}

/**
 * What the bin row says about itself.
 *
 * The zero case has to explain what the row is *for*, not merely that it is
 * empty: that is the state a user reading Settings to learn what the app can do
 * will normally find it in.
 */
internal fun deletedSubtitle(state: MoreUiState): String = when {
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
private fun DeleteReceiptsDialog(state: MoreUiState, onEvent: (MoreEvent) -> Unit) {
    LfDialog(
        title = deleteReceiptsTitle(state),
        body = deleteReceiptsBody(state),
        confirmText = "Delete",
        // Warning emphasis also stops an outside tap from standing in for an
        // answer, which on an irreversible action would defeat the point.
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(MoreEvent.ReceiptDeleteConfirmed) },
        onDismiss = { onEvent(MoreEvent.ReceiptDeleteDismissed) },
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
internal fun backupSubtitle(state: MoreUiState, locale: Locale = Locale.getDefault()): String {
    val last = state.lastBackupAt
        ?: return "No backup yet. Your data exists only on this phone."
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(last))
    return "Last backup $date. Asks for your 24 words."
}

/** The delete dialog's title, with the count named (see [DeleteReceiptsDialog]). */
internal fun deleteReceiptsTitle(state: MoreUiState): String {
    val count = state.receipts.count
    return if (count == 1) "Delete 1 receipt image?" else "Delete $count receipt images?"
}

/**
 * The delete dialog's body. Separate from the composable so the promise it
 * makes — or refuses to make — is pinned by a test rather than by review.
 */
internal fun deleteReceiptsBody(state: MoreUiState): String =
    "This frees ${formatSize(state.receipts.bytes)} and cannot be undone. " +
        "Your entries and their amounts are untouched — only the photographs go. " +
        "This phone keeps no other copy. Photos included in a backup made with " +
        "\u201cBack up now\u201d stay in that backup folder."

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
internal fun receiptsSubtitle(state: MoreUiState): String = when {
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
internal fun captureSubtitle(state: MoreUiState): String = when (state.captureHealth) {
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
private fun MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
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
private fun MorePreview() {
    LfTheme {
        MoreScreen(
            state = MoreUiState(
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
            onEvent = {},
        )
    }
}

/** An empty bin — the state most users see most of the time. */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun MoreEmptyBinPreview() {
    LfTheme {
        MoreScreen(
            state = MoreUiState(deletedCount = 0, isLoaded = true),
            onCategories = {},
            onBudgets = {},
            onExport = {},
            onDeletedEntries = {},
            onNotificationAccess = {},
            onBackUp = {},
            onEvent = {},
        )
    }
}
