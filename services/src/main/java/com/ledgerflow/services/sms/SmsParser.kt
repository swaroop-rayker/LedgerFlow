package com.ledgerflow.services.sms

import timber.log.Timber
import java.util.Calendar

object SmsParser {

    // Keep ParsedTransaction for backward compatibility
    data class ParsedTransaction(
        val amountCents: Long,
        val merchantName: String,
        val paymentMethod: String?,
        val referenceNumber: String?,
        val timestamp: Long
    )

    /**
     * Legacy parse function for backward compatibility with repository interfaces.
     */
    fun parse(smsBody: String): ParsedTransaction? {
        val candidate = parseToCandidate(smsBody) ?: return null
        return ParsedTransaction(
            amountCents = candidate.amountCents,
            merchantName = candidate.merchantName ?: "Unknown Merchant",
            paymentMethod = candidate.paymentMode ?: candidate.accountNumber,
            referenceNumber = candidate.referenceNumber,
            timestamp = candidate.timestamp
        )
    }

    /**
     * Modern multi-stage parsing pipeline.
     */
    fun parseToCandidate(smsBody: String): TransactionCandidate? {
        Timber.d("SMS received: %s", smsBody)

        // Stage 1: Normalize SMS (preserves casing but fixes spacing)
        val normalized = SmsNormalizer.normalize(smsBody)
        Timber.d("Normalized SMS: %s", normalized)

        // Filter out OTPs, verification codes, and common spam
        val lowerText = normalized.lowercase()
        if (lowerText.contains("otp") || 
            lowerText.contains("verification code") || 
            lowerText.contains("win cash") || 
            lowerText.contains("claim reward") || 
            lowerText.contains("win up to") ||
            lowerText.contains("click here")
        ) {
            Timber.d("Parser rejected: Contains OTP, verification code, or spam content.")
            return null
        }

        // Stage 2: Detect transaction type
        val type = TransactionTypeDetector.detect(normalized)
        Timber.d("Transaction type detected: %s", type)
        if (type == TransactionType.UNKNOWN) {
            Timber.d("Parser rejected: Unknown transaction type.")
            return null
        }

        // Stage 3: Extract amount
        val amountDouble = AmountExtractor.extract(normalized)
        if (amountDouble == null || amountDouble <= 0.0) {
            Timber.d("Parser rejected: Failed to extract valid amount or amount is 0.")
            return null
        }
        val amountCents = (amountDouble * 100).toLong()
        Timber.d("Amount extracted: %d cents (₹ %.2f)", amountCents, amountDouble)

        // Stage 4: Extract merchant/payee (using case-preserved normalized string)
        val merchant = MerchantExtractor.extract(normalized)
        Timber.d("Merchant extracted: %s", merchant)

        // Stage 5: Extract account number
        val account = AccountExtractor.extract(normalized)
        Timber.d("Account extracted: %s", account)

        // Stage 6: Extract reference number
        val reference = ReferenceExtractor.extract(normalized)
        Timber.d("Reference extracted: %s", reference)

        // Stage 7: Extract payment mode
        val paymentMode = PaymentModeExtractor.extract(normalized, type)
        Timber.d("Payment mode extracted: %s", paymentMode)

        // Stage 8: Extract date/time
        val timestamp = DateExtractor.extract(normalized) ?: System.currentTimeMillis()
        Timber.d("Timestamp extracted: %d", timestamp)

        // Stage 9: Generate fingerprint (SHA-256 for deduplication)
        val fingerprint = generateFingerprint(amountCents, timestamp, reference, account, merchant)
        Timber.d("Fingerprint generated: %s", fingerprint)

        val candidate = TransactionCandidate(
            smsBody = smsBody,
            normalizedBody = normalized,
            type = type,
            amountCents = amountCents,
            merchantName = merchant,
            accountNumber = account,
            referenceNumber = reference,
            paymentMode = paymentMode,
            timestamp = timestamp,
            fingerprint = fingerprint
        )
        
        Timber.d("Transaction candidate created: %s", candidate)
        return candidate
    }

    private fun generateFingerprint(
        amount: Long,
        timestamp: Long,
        ref: String?,
        acc: String?,
        merchant: String?
    ): String {
        val raw = "$amount|$timestamp|${ref ?: ""}|${acc ?: ""}|${merchant ?: ""}"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
