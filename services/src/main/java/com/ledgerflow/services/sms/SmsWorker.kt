package com.ledgerflow.services.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ledgerflow.domain.model.PendingTransaction
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class SmsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsWorkerEntryPoint {
        fun savePendingTransactionUseCase(): com.ledgerflow.domain.usecase.SavePendingTransactionUseCase
        fun suggestCategoryUseCase(): com.ledgerflow.domain.usecase.SuggestCategoryUseCase
    }

    companion object {
        const val KEY_SMS_BODY = "sms_body"
    }

    override suspend fun doWork(): Result {
        val smsBody = inputData.getString(KEY_SMS_BODY) ?: return Result.failure()
        Timber.d("SMS received: %s", smsBody)
        
        try {
            val parsedTxn = SmsParser.parse(smsBody)
            if (parsedTxn != null) {
                Timber.d("SMS parsed successfully: %s", parsedTxn)
                
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    SmsWorkerEntryPoint::class.java
                )
                val saveUseCase = entryPoint.savePendingTransactionUseCase()
                val suggestUseCase = entryPoint.suggestCategoryUseCase()

                val rawMerchant = parsedTxn.merchantName
                val canonicalMerchant = com.ledgerflow.core.common.util.MerchantNormalizer.normalize(rawMerchant)
                val (suggestedCategory, suggestedSubcategory) = suggestUseCase(canonicalMerchant, smsBody)

                // Calculate confidence score
                var confidence = 100
                if (rawMerchant.contains("Unknown", ignoreCase = true) || 
                    rawMerchant.contains("UPI", ignoreCase = true) ||
                    rawMerchant.contains("Bank", ignoreCase = true)
                ) {
                    confidence -= 30
                }
                if (suggestedCategory == "Others") {
                    confidence -= 20
                }
                if (parsedTxn.paymentMethod == null) {
                    confidence -= 15
                }
                confidence = confidence.coerceIn(10, 100)

                val pending = PendingTransaction(
                    amount = parsedTxn.amountCents,
                    merchant = canonicalMerchant,
                    category = suggestedCategory,
                    subcategory = suggestedSubcategory,
                    paymentMethod = parsedTxn.paymentMethod,
                    reference = parsedTxn.referenceNumber,
                    timestamp = parsedTxn.timestamp,
                    confidence = confidence,
                    status = "PENDING",
                    rawMerchant = rawMerchant
                )

                val saveResult = saveUseCase(pending)
                if (saveResult is com.ledgerflow.domain.model.Result.Success) {
                    val pendingId = saveResult.data
                    Timber.d("Saved PendingTransaction: ID = %d", pendingId)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            applicationContext,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        Timber.w("POST_NOTIFICATIONS permission not granted. Cannot post notification.")
                    } else {
                        sendNotification(pendingId, pending)
                    }
                } else {
                    Timber.e("Failed to save pending transaction to database.")
                }
            } else {
                Timber.d("SMS rejected by parser: Did not match transaction structure.")
            }
            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing SMS in background worker")
            return Result.failure()
        }
    }

    private fun sendNotification(pendingId: Long, pending: PendingTransaction) {
        val channelId = "sms_transactions_channel"
        val notificationId = 1001

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SMS Transactions"
            val descriptionText = "Notifications for auto-parsed bank SMS transactions"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            manager.createNotificationChannel(channel)
        }

        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Attach ONLY the generated pending transaction ID
            putExtra("pending_transaction_id", pendingId)
        }

        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val amountFormatted = com.ledgerflow.core.common.util.CurrencyUtils.formatCents(pending.amount)
        val textContent = "Detected transaction of $amountFormatted at ${pending.merchant}. Tap to review."

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New Expense Detected")
            .setContentText(textContent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            manager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException: missing permission for posting notification")
        }
    }
}
