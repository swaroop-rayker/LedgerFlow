package com.ledgerflow.services.sms

object ReferenceExtractor {
    private val refRegex = Regex("(?i)(?:ref|reference|txn\\s*id|transaction\\s*id|upi\\s*ref|imps\\s*ref)\\s*(?:no\\.?)?\\s*[:\\s-]*\\s*([a-z0-9]{4,22})")

    fun extract(normalizedSms: String): String? {
        val match = refRegex.find(normalizedSms) ?: return null
        return match.groupValues[1]
    }
}
