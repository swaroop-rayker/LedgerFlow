package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.diagnostics.DiagnosticsWindow
import com.ledgerflow.core.domain.diagnostics.IngestDiagnostics
import com.ledgerflow.core.domain.diagnostics.IngestDiagnosticsRepository
import javax.inject.Inject

/**
 * The ingest diagnostics report for one window (SPEC.md §13 P5).
 *
 * Reads the clock once, so the window, the stuck line and the oldest waiting
 * candidate's age are all measured from the same moment.
 *
 * @return null when the vault cannot be opened.
 */
public class GetIngestDiagnosticsUseCase @Inject constructor(
    private val diagnostics: IngestDiagnosticsRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(window: DiagnosticsWindow): IngestDiagnostics? {
        val now = clock.nowMillis()
        return diagnostics.snapshot(sinceMillis = window.sinceMillis(now), nowMillis = now)
    }
}
