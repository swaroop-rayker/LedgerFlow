package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.repository.MerchantPreferenceRepository
import com.ledgerflow.domain.repository.PendingTransactionRepository
import com.ledgerflow.domain.repository.TransactionRepository
import javax.inject.Inject

class ApprovePendingTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val pendingTransactionRepository: PendingTransactionRepository,
    private val merchantPreferenceRepository: MerchantPreferenceRepository
) {
    suspend operator fun invoke(
        pendingId: Long,
        approvedTxn: Transaction
    ): Result<Unit> {
        val saveRes = transactionRepository.saveTransaction(approvedTxn)
        if (saveRes is Result.Failure) {
            return Result.Failure.DatabaseError(
                (saveRes as? Result.Failure.DatabaseError)?.exception ?: Exception("Failed to save transaction")
            )
        }

        // Delete the pending record
        pendingTransactionRepository.deletePendingTransaction(pendingId)

        // Learn/update merchant preference
        val prefRes = merchantPreferenceRepository.getMerchantPreference(approvedTxn.merchant)
        if (prefRes is Result.Success) {
            val pref = prefRes.data
            if (pref != null) {
                val updated = pref.copy(
                    preferredCategory = approvedTxn.category,
                    preferredSubcategory = approvedTxn.subcategory,
                    lastUsed = System.currentTimeMillis(),
                    usageCount = pref.usageCount + 1
                )
                merchantPreferenceRepository.saveMerchantPreference(updated)
            } else {
                val newPref = MerchantPreference(
                    merchant = approvedTxn.merchant,
                    preferredCategory = approvedTxn.category,
                    preferredSubcategory = approvedTxn.subcategory,
                    lastUsed = System.currentTimeMillis(),
                    usageCount = 1
                )
                merchantPreferenceRepository.saveMerchantPreference(newPref)
            }
        }

        return Result.Success(Unit)
    }
}
