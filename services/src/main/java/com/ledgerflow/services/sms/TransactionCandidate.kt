package com.ledgerflow.services.sms

enum class TransactionType {
    DEBIT,
    CREDIT,
    SELF_TRANSFER,
    UNKNOWN
}

data class TransactionCandidate(
    val smsBody: String,
    val normalizedBody: String,
    val type: TransactionType,
    val amountCents: Long, // in cents/paise
    val merchantName: String?,
    val accountNumber: String?,
    val referenceNumber: String?,
    val paymentMode: String?,
    val timestamp: Long,
    val fingerprint: String
)
