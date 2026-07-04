package com.ledgerflow.services.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ledgerflow.data.db.dao.PendingTransactionDao
import com.ledgerflow.data.db.entity.PendingTransactionEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.Calendar

class SmsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsWorkerEntryPoint {
        fun pendingTransactionDao(): PendingTransactionDao
    }

    companion object {
        const val KEY_SMS_BODY = "sms_body"
    }

    override suspend fun doWork(): Result {
        val smsBody = inputData.getString(KEY_SMS_BODY) ?: return Result.failure()
        
        try {
            val parsedTxn = SmsParser.parse(smsBody)
            if (parsedTxn != null) {
                // Fetch DAO from Hilt entrypoint dynamically
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    SmsWorkerEntryPoint::class.java
                )
                val pendingTransactionDao = entryPoint.pendingTransactionDao()

                val pendingEntity = PendingTransactionEntity(
                    timestamp = Calendar.getInstance().timeInMillis,
                    amount = parsedTxn.amountCents,
                    merchant = parsedTxn.merchantName,
                    paymentMethod = parsedTxn.paymentMethod
                )
                
                pendingTransactionDao.insertPendingTransaction(pendingEntity)
                Timber.d("SMS parsed and added to pending queue successfully.")
            }
            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing SMS in background worker")
            return Result.failure()
        }
    }
}
