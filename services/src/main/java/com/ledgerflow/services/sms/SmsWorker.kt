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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

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

                // Trigger system notification
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Timber.w("POST_NOTIFICATIONS permission not granted. Cannot post notification.")
                } else {
                    sendNotification(parsedTxn)
                }
            }
            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing SMS in background worker")
            return Result.failure()
        }
    }

    private fun sendNotification(parsedTxn: SmsParser.ParsedTransaction) {
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

        // Retrieve package launcher intent to open app when notification is clicked
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val amountFormatted = com.ledgerflow.core.common.util.CurrencyUtils.formatCents(parsedTxn.amountCents)
        val textContent = "Detected transaction of $amountFormatted at ${parsedTxn.merchantName} via ${parsedTxn.paymentMethod ?: "Cash"}."

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New Transaction Detected")
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
