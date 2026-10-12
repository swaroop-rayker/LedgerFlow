package com.ledgerflow.core.testing.ingest

import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.CaptureGapStore
import kotlinx.coroutines.flow.MutableStateFlow

/** [CaptureGapStore] in memory, with the window rules the DataStore applies. */
public class FakeCaptureGapStore(initial: CaptureGap? = null) : CaptureGapStore {

    public val state: MutableStateFlow<CaptureGap?> = MutableStateFlow(initial)

    override val gap: MutableStateFlow<CaptureGap?> = state

    override suspend fun recordMissed(atMillis: Long) {
        state.value = state.value
            ?.let { it.copy(lastMissedAt = maxOf(it.lastMissedAt, atMillis), missed = it.missed + 1) }
            ?: CaptureGap(firstMissedAt = atMillis, lastMissedAt = atMillis, missed = 1)
    }

    override suspend fun clear() {
        state.value = null
    }
}
