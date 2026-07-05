package com.ledgerflow.domain.usecase

   import com.ledgerflow.domain.model.PendingTransaction
   import com.ledgerflow.domain.repository.PendingTransactionRepository
   import kotlinx.coroutines.flow.Flow
   import javax.inject.Inject

   class GetPendingTransactionsFlowUseCase @Inject constructor(
       private val repository: PendingTransactionRepository
   ) {
       operator fun invoke(): Flow<List<PendingTransaction>> = repository.getPendingTransactionsFlow()
   }
   
