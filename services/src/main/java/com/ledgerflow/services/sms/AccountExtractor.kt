package com.ledgerflow.services.sms

object AccountExtractor {
    private val accountRegex = Regex("(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?|ending\\s+in|ending)?\\s*([xX*]*\\d{3,6})")
    private val fromAccountRegex = Regex("(?i)(?:from|debited from)\\s+(?:a/c|acct|account|card)\\s*([xX*]*\\d{3,6})")
    private val toAccountRegex = Regex("(?i)(?:to|credited to)\\s+(?:a/c|acct|account|card)\\s*([xX*]*\\d{3,6})")

    fun extract(normalizedSms: String): String? {
        val fromMatch = fromAccountRegex.find(normalizedSms)
        if (fromMatch != null) return sanitize(fromMatch.groupValues[1])

        val match = accountRegex.find(normalizedSms) ?: return null
        return sanitize(match.groupValues[1])
    }

    fun extractDestinationAccount(normalizedSms: String): String? {
        val toMatch = toAccountRegex.find(normalizedSms) ?: return null
        return sanitize(toMatch.groupValues[1])
    }

    private fun sanitize(raw: String): String {
        // Strip out masking characters like X or * to retrieve only the digit suffix
        return raw.replace(Regex("[xX*]"), "").trim()
    }
}
