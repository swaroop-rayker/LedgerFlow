package com.ledgerflow.feature.settings.drive

import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.DisplayLocale
import com.ledgerflow.core.domain.drive.DriveOutcome
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.domain.drive.DriveUnavailable
import java.util.Locale

/** How the Drive page and the Data row read the status's tone. */
internal enum class DriveTone { Neutral, Good, NeedsYou }

/**
 * The one sentence Drive backup says about itself, on the Data row and atop
 * its page (ADR-0034 §5). Worded like the nightly backup's: what is true now,
 * then what happens next.
 */
internal fun driveSentence(status: DriveStatus, locale: Locale = DisplayLocale.current()): String {
    if (!status.enabled) return "Off. Keep a copy of each nightly backup in your Google Drive."
    val last = status.lastCopiedAt
        ?.let { " Last copied ${DisplayDates.mediumDateTime(it, locale = locale)}." }
        .orEmpty()
    val attempt = status.lastAttempt
    return when (val outcome = attempt?.outcome) {
        null, DriveOutcome.Off -> "On. The first copy goes up when there's a connection."
        is DriveOutcome.Copied -> "Copied ${DisplayDates.mediumDateTime(attempt.at, locale = locale)}."
        DriveOutcome.NothingToCopy -> "On. Nothing to copy until the phone has a backup."
        DriveOutcome.NeedsSignIn -> "Sign in again to keep copying.$last"
        is DriveOutcome.Unavailable -> unavailableSentence(outcome.reason) + last
        is DriveOutcome.Failed -> if (outcome.retryable) {
            "Couldn't reach Drive. Trying again.$last"
        } else {
            "Couldn't copy to Drive. Tries again tonight.$last"
        }
    }
}

internal fun driveTone(status: DriveStatus): DriveTone = when {
    !status.enabled -> DriveTone.Neutral
    status.lastAttempt?.outcome is DriveOutcome.Copied -> DriveTone.Good
    status.lastAttempt?.outcome == DriveOutcome.NeedsSignIn ||
        status.lastAttempt?.outcome is DriveOutcome.Unavailable -> DriveTone.NeedsYou
    else -> DriveTone.Neutral
}

internal fun unavailableSentence(reason: DriveUnavailable): String = when (reason) {
    DriveUnavailable.NoPlayServices -> "Needs Google Play services, which this phone doesn't have."
    DriveUnavailable.NotConfigured -> "Google sign-in isn't set up for this build."
    DriveUnavailable.Failed -> "Couldn't reach Google. Tries again tonight."
}
