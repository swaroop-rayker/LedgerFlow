package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.CaptureGapStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The open capture gap for Home's banner, or null (SPEC.md §16 Q16). */
public class ObserveCaptureGapUseCase @Inject constructor(
    private val store: CaptureGapStore,
) {
    public operator fun invoke(): Flow<CaptureGap?> = store.gap
}
