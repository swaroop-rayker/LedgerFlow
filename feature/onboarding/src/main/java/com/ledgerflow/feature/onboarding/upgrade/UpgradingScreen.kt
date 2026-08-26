package com.ledgerflow.feature.onboarding.upgrade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.vault.UpgradeBlockReason
import kotlin.math.ceil

/**
 * The Upgrading screen (SPEC.md §8.1, ADR-0019).
 *
 * A schema migration is running, and this is the whole app while it does. It is
 * **not cancellable** — a half-taken rollback point is worse than none — and it
 * must never present as a frozen app, which is why it says what is happening
 * rather than showing a bare spinner.
 *
 * **There is no progress bar, and that is ADR-0019's doing.** §8.1 asked for
 * determinate progress in bytes, sized against a full export-and-reparse of
 * every row. The snapshot is now a file copy, which is effectively instant, so a
 * byte counter would flash past and mean nothing. If the database ever grows
 * enough for the copy to be perceptible, progress gets added to a mechanism that
 * can actually report it.
 *
 * Stateless like every other screen (CLAUDE.md §5). It takes a state and, on the
 * blocked paths, one retry callback — there is no ViewModel because there is no
 * decision to make here: the vault layer has already acted.
 */
@Composable
public fun UpgradingScreen(
    from: Int,
    to: Int,
    modifier: Modifier = Modifier,
) {
    UpgradeFrame(
        title = "Updating your ledger",
        body = "Moving your data from format $from to $to. This only happens after an " +
            "app update and usually takes a moment.",
        modifier = modifier,
    ) {
        LfCard {
            Text(
                // Said plainly, because the one thing a user must not do here is
                // force-stop the app halfway through a migration.
                text = "A copy of your data was saved first, so nothing is at risk if " +
                    "this is interrupted. Please leave the app open.",
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * The upgrade did not go ahead, and the database was **not** migrated
 * (SPEC.md §8.1).
 *
 * Blocking is the correct outcome: migrating with no rollback point is the whole
 * of what BUG8 is about. Each reason gets its own sentence and its own remedy —
 * a shared "something went wrong" is the screen that makes a recoverable state
 * feel terminal.
 */
@Composable
public fun UpgradeBlockedScreen(
    reason: UpgradeBlockReason,
    modifier: Modifier = Modifier,
) {
    val copy = reason.explain()
    UpgradeFrame(title = copy.title, body = copy.body, modifier = modifier) {
        LfCard {
            Text(
                text = copy.detail,
                style = LfTheme.typography.bodyM,
                color = LfTheme.colors.textSecondary,
            )
        }
    }
}

private data class UpgradeCopy(val title: String, val body: String, val detail: String)

/**
 * **Nothing here offers to back up, and nothing may.** The app cannot write a
 * `.lfbk` unattended — it is phrase-derived and the app never holds the phrase
 * (ADR-0011) — so the honest instruction is the one the purge dialog gives:
 * export, yourself.
 */
private fun UpgradeBlockReason.explain(): UpgradeCopy = when (this) {
    is UpgradeBlockReason.InsufficientStorage -> UpgradeCopy(
        title = "Free up space to finish updating",
        body = "The update needs room to save a copy of your data before it changes " +
            "anything. Your ledger is untouched and nothing has been lost.",
        detail = "Needs about ${megabytes(requiredBytes)} MB free; " +
            "${megabytes(availableBytes)} MB is available. Free up some space and " +
            "reopen the app.",
    )

    UpgradeBlockReason.SnapshotFailed -> UpgradeCopy(
        title = "The update did not start",
        body = "A copy of your data could not be saved, so the update was not attempted. " +
            "Your ledger is exactly as it was.",
        detail = "Reopening the app will try again. If it keeps happening, export your " +
            "data from More → Export before updating.",
    )

    is UpgradeBlockReason.MigrationFailed -> if (restored) {
        UpgradeCopy(
            title = "The update did not finish",
            body = "Something went wrong partway through, so your data was put back the " +
                "way it was. Nothing was lost.",
            detail = "You are still on the previous format. Reopening the app will try " +
                "again; export your data from More → Export first if you would rather " +
                "have a copy of your own.",
        )
    } else {
        UpgradeCopy(
            title = "The update did not finish",
            body = "Something went wrong partway through and the saved copy could not be " +
                "put back automatically.",
            // Not softened. This is the one case where the user needs to know
            // before they touch anything else.
            detail = "Do not reinstall the app. Your twenty-four word recovery phrase and " +
                "any backup you exported are what restore this — reinstalling without " +
                "them will not.",
        )
    }

    is UpgradeBlockReason.Downgrade -> UpgradeCopy(
        title = "This version is older than your data",
        body = "Your ledger was written by a newer version of LedgerFlow. Opening it with " +
            "this one could damage it, so it has not been opened.",
        detail = "Install the newer version again. Your data is untouched — it is on " +
            "format $onDisk and this build understands $supported.",
    )
}

/** Rounded up, so "needs 12 MB" never means "actually needed 12.4". */
private fun megabytes(bytes: Long): Long =
    ceil(bytes / BYTES_PER_MEGABYTE).toLong()

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/**
 * The shared frame.
 *
 * No bottom bar and no primary action: every state here is one the *app* has to
 * resolve, not the user, so a button would be a lie about who is in control.
 * Scrollable because at font scale 2.0 the storage copy is genuinely long, and
 * §9.6 asks for that to work rather than merely render.
 */
@Composable
private fun UpgradeFrame(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    LfScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LfTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
                Text(
                    text = title,
                    style = LfTheme.typography.displayL,
                    color = LfTheme.colors.textPrimary,
                )
                Text(
                    text = body,
                    style = LfTheme.typography.bodyL,
                    color = LfTheme.colors.textSecondary,
                )
            }
            detail()
        }
    }
}

// ── Previews (CLAUDE.md §5: every top-level screen) ───────────────────────

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun UpgradingPreview() {
    LfTheme { UpgradingScreen(from = 5, to = 6) }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun UpgradeStoragePreview() {
    LfTheme {
        UpgradeBlockedScreen(
            reason = UpgradeBlockReason.InsufficientStorage(
                requiredBytes = 42L * 1024 * 1024,
                availableBytes = 3L * 1024 * 1024,
            ),
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun UpgradeUnrestoredPreview() {
    LfTheme {
        UpgradeBlockedScreen(reason = UpgradeBlockReason.MigrationFailed(restored = false))
    }
}
