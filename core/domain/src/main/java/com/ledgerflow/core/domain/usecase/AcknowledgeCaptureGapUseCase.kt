package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.ingest.CaptureGapStore
import javax.inject.Inject

/**
 * The user has read the capture-gap banner ("Got it"), so it closes.
 *
 * Only the user closes it. A later successful capture does not: the window it
 * reports is in the past and stays missing whatever happens next, and clearing
 * it automatically would turn a loss the user has not seen into a silent one.
 */
public class AcknowledgeCaptureGapUseCase @Inject constructor(
    private val store: CaptureGapStore,
) {
    public suspend operator fun invoke(): Unit = store.clear()
}
