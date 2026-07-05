package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.PendingTransaction
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.PendingTransactionRepository
import javax.inject.Inject

class GetPendingTransactionByIdUseCase @Inject constructor(
    private val repository: PendingTransactionRepository
) {
    suspend operator fun invoke(id: Long): Result<PendingTransaction?> = repository.getPendingTransactionById(id)
}
