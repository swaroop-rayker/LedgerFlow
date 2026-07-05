package com.ledgerflow.services.sms

object TransactionTypeDetector {
    private val selfTransferRegexes = listOf(
        Regex("(?i)debited\\s+from\\s+.*\\s+credited\\s+to"),
        Regex("(?i)transferr?e?d?\\s+(?:.*\\s+)?to\\s+(?:your\\s+)?(?:other\\s+)?account"),
        Regex("(?i)transferr?e?d?\\s+to\\s+self"),
        Regex("(?i)self\\s*transfer"),
        Regex("(?i)own\\s*account\\s*transfer"),
        Regex("(?i)transfer\\s+to\\s+self")
    )

    private val debitKeywords = listOf(
        "debited", "debit", "spent", "purchase", "withdrawn", "withdrawal", "deducted", 
        "deduction", "sent to", "paid to", "paid for", "txn at", "transfer to",
        "payment of", "deduct", "sent r", "withdrew", "transfer", "paid", "sent"
    )
    
    private val creditKeywords = listOf(
        "credited", "credit", "received", "refund", "cashback", "deposited", 
        "salary", "added to a/c", "interest earned", "interest credited", "deposit"
    )

    fun detect(normalizedSms: String): TransactionType {
        val lowerText = normalizedSms.lowercase()

        // Check Self Transfer first
        if (selfTransferRegexes.any { it.containsMatchIn(normalizedSms) }) {
            return TransactionType.SELF_TRANSFER
        }

        val isDebit = debitKeywords.any { lowerText.contains(it) }
        val isCredit = creditKeywords.any { lowerText.contains(it) }

        return when {
            isDebit && isCredit -> TransactionType.DEBIT // Default to debit for mixed matches
            isDebit -> TransactionType.DEBIT
            isCredit -> TransactionType.CREDIT
            else -> TransactionType.UNKNOWN
        }
    }
}
