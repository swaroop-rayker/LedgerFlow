package com.ledgerflow.services.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import timber.log.Timber

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val smsBody = message.messageBody ?: continue
                
                // Pass raw SMS body to background worker
                val inputData = Data.Builder()
                    .putString(SmsWorker.KEY_SMS_BODY, smsBody)
                    .build()
                
                val workRequest = OneTimeWorkRequestBuilder<SmsWorker>()
                    .setInputData(inputData)
                    .build()
                
                WorkManager.getInstance(context).enqueue(workRequest)
                Timber.d("SMS Broadcast received. Enqueued background parsing worker.")
            }
        }
    }
}
