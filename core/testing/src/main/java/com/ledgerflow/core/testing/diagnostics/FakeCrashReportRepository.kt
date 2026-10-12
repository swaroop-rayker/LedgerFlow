package com.ledgerflow.core.testing.diagnostics

import com.ledgerflow.core.domain.diagnostics.CrashReportRepository
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import kotlinx.coroutines.flow.MutableStateFlow

/** [CrashReportRepository] in memory: [exportSucceeds] decides what a save does. */
public class FakeCrashReportRepository(initial: CrashSummary? = null) : CrashReportRepository {

    public val state: MutableStateFlow<CrashSummary?> = MutableStateFlow(initial)
    public var exportSucceeds: Boolean = true
    public val exportedTo: MutableList<String> = mutableListOf()

    override val summary: MutableStateFlow<CrashSummary?> = state

    override suspend fun exportTo(destinationUri: String): Boolean {
        exportedTo += destinationUri
        return exportSucceeds
    }

    override fun suggestedFileName(): String = "LedgerFlow-crash-reports-2026-10-12.txt"

    override suspend fun dismissAll() {
        state.value = null
    }
}
