package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.PendingTransactionRepository
import javax.inject.Inject

class DeletePendingTransactionUseCase @Inject constructor(
    private val repository: PendingTransactionRepository
) {
    suspend operator fun invoke(id: Long): Result<Unit> = repository.deletePendingTransaction(id)
}
