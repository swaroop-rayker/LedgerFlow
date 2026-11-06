package com.ledgerflow.feature.inbox

import com.ledgerflow.core.domain.statement.ApproveImportBatchUseCase
import com.ledgerflow.core.domain.statement.ObserveImportBatchesUseCase
import javax.inject.Inject

/**
 * "Approve all" per import (V2-4, D8): the panels above the queue and the one
 * approval they lead to, handed to [InboxViewModel] as one dependency.
 */
public class ImportBatchActions @Inject constructor(
    public val observe: ObserveImportBatchesUseCase,
    public val approveAll: ApproveImportBatchUseCase,
)
