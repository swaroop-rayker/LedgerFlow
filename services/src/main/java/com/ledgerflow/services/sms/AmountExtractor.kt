package com.ledgerflow.services.sms

object AmountExtractor {
    private val amountRegex = Regex("(?i)(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d+)?)")

    fun extract(normalizedSms: String): Double? {
        val match = amountRegex.find(normalizedSms) ?: return null
        val rawAmount = match.groupValues[1].replace(",", "")
        return rawAmount.toDoubleOrNull()
    }
}
