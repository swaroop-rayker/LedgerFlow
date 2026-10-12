package com.ledgerflow.core.domain.diagnostics

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The crash reports waiting to be offered on Home (BUG7(c)). */
public class ObserveCrashReportsUseCase @Inject constructor(
    private val repository: CrashReportRepository,
) {
    public operator fun invoke(): Flow<CrashSummary?> = repository.summary
}

/**
 * The user chose a file: write the reports there, then let them go.
 *
 * Deleted only after a write that succeeded, so a failed save leaves the
 * reports to try again rather than losing them.
 */
public class SaveCrashReportsUseCase @Inject constructor(
    private val repository: CrashReportRepository,
) {
    public suspend operator fun invoke(destinationUri: String): Boolean {
        val saved = repository.exportTo(destinationUri)
        if (saved) repository.dismissAll()
        return saved
    }

    public fun suggestedFileName(): String = repository.suggestedFileName()
}

/** "Dismiss": the user does not want them. */
public class DismissCrashReportsUseCase @Inject constructor(
    private val repository: CrashReportRepository,
) {
    public suspend operator fun invoke(): Unit = repository.dismissAll()
}
