package com.ledgerflow.services.sms

import java.util.regex.Pattern

object SmsParser {
    // Standard Regex Patterns for banking SMS alerts (debited, spent, UPI)
    private val debitedPattern = Pattern.compile(
        "(?i)(?:Rs\\.?|INR|₹)\\s*([\\d,]+\\.?\\d*)\\s*(?:debited|spent|spent\\s+on|txn\\s+at)\\s*(?:from|for|on|at|using)?\\s*([^\\n]*)"
    )
    
    private val upiPattern = Pattern.compile(
        "(?i)spent\\s*Rs\\.?\\s*([\\d,]+\\.?\\d*)\\s*on\\s*([^\\n]*)\\s*(?:UPI|Ref)"
    )

    data class ParsedTransaction(
        val amountCents: Long,
        val merchantName: String,
        val paymentMethod: String?
    )

    fun parse(smsBody: String): ParsedTransaction? {
        // Filter out spam, promotions, and OTPs
        if (smsBody.contains("OTP", ignoreCase = true) || 
            smsBody.contains("verification code", ignoreCase = true) || 
            smsBody.contains("win cash", ignoreCase = true)
        ) {
            return null
        }

        // Try Debit matcher
        val debitMatcher = debitedPattern.matcher(smsBody)
        if (debitMatcher.find()) {
            val amountStr = debitMatcher.group(1)?.replace(",", "")
            val amount = amountStr?.toDoubleOrNull() ?: return null
            val merchant = debitMatcher.group(2)?.trim()?.take(50) ?: "Unknown Merchant"
            
            // Extract account suffix if available
            val account = if (smsBody.contains("A/c", ignoreCase = true)) {
                "A/c " + smsBody.substringAfter("A/c").take(6).trim()
            } else null
            
            return ParsedTransaction(
                amountCents = (amount * 100).toLong(),
                merchantName = merchant,
                paymentMethod = account
            )
        }

        // Try UPI matcher
        val upiMatcher = upiPattern.matcher(smsBody)
        if (upiMatcher.find()) {
            val amountStr = upiMatcher.group(1)?.replace(",", "")
            val amount = amountStr?.toDoubleOrNull() ?: return null
            val merchant = upiMatcher.group(2)?.trim()?.take(50) ?: "Unknown Merchant"
            return ParsedTransaction(
                amountCents = (amount * 100).toLong(),
                merchantName = merchant,
                paymentMethod = "UPI"
            )
        }

        return null
    }
}
