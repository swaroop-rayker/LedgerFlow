package com.ledgerflow.services.sms

object MerchantExtractor {
    private val patterns = listOf(
        Regex("(?i)(?:paid|sent|debited|transferred)\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]+\\s+to\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)info:\\s*(?:upi/)?([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)spent\\s+at\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)spent\\s+on\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)purchase\\s+at\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)paid\\s+to\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)transfer\\s+to\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)sent\\s+to\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)credited\\s+by\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)refunded\\s+by\\s+([a-zA-Z0-9\\s&'@.-]{2,30})"),
        Regex("(?i)transferred\\s+from\\s+([a-zA-Z0-9\\s&'@.-]{2,30})")
    )

    private val fallbackPatterns = listOf(
        Regex("(?i)\\bat\\s+([a-zA-Z0-9@.-]+)"),
        Regex("(?i)\\bto\\s+([a-zA-Z0-9@.-]+)"),
        Regex("(?i)\\bon\\s+([a-zA-Z0-9@.-]+)")
    )

    fun extract(smsBody: String): String? {
        for (pattern in patterns) {
            val match = pattern.find(smsBody)
            if (match != null) {
                val merchant = sanitize(match.groupValues[1])
                if (isValidMerchant(merchant)) {
                    return merchant
                }
            }
        }

        for (pattern in fallbackPatterns) {
            val match = pattern.find(smsBody)
            if (match != null) {
                val merchant = sanitize(match.groupValues[1])
                if (isValidMerchant(merchant)) {
                    return merchant
                }
            }
        }

        return null
    }

    private fun sanitize(raw: String): String {
        var clean = raw.trim()
        val words = clean.split(Regex("\\s+"))
        val stopWords = setOf(
            "via", "using", "on", "ref", "card", "from", "balance", 
            "avbl", "a/c", "for", "at", "to", "in", "by", "ending", 
            "with", "credited", "debited", "spent", "info"
        )
        val builder = mutableListOf<String>()
        for (word in words) {
            val normalWord = word.lowercase().replace(Regex("[^a-z]"), "")
            if (stopWords.contains(normalWord)) {
                break
            }
            builder.add(word)
        }
        clean = builder.joinToString(" ")
        // Strip trailing dots, commas, hyphens, and spaces
        clean = clean.replace(Regex("[.,\\s-]+$"), "")
        return clean.trim()
    }

    private fun isValidMerchant(name: String): Boolean {
        val lower = name.lowercase()
        val blacklisted = listOf(
            "your", "my", "our", "the", "a/c", "account", "card", "rs", "inr", "ref", "txn", "val"
        )
        if (blacklisted.contains(lower)) return false
        if (name.length < 2) return false
        return true
    }
}
