package com.ledgerflow.services.sms

object PaymentModeExtractor {
    fun extract(normalizedSms: String, type: TransactionType): String {
        val text = normalizedSms.lowercase()
        return when {
            text.contains("cash deposit") || text.contains("deposit cash") -> "Cash Deposit"
            text.contains("atm") -> "ATM"
            text.contains("cash withdrawal") -> "Cash Withdrawal"
            text.contains("wallet") -> "Wallet"
            text.contains("upi") || text.contains("gpay") || text.contains("phonepe") || text.contains("paytm") -> "UPI"
            text.contains("imps") -> "IMPS"
            text.contains("neft") -> "NEFT"
            text.contains("rtgs") -> "RTGS"
            text.contains("pos") || text.contains("merchant") -> "POS"
            text.contains("card") || text.contains("visa") || text.contains("mastercard") || text.contains("rupay") -> "Card"
            type == TransactionType.SELF_TRANSFER -> "Self Transfer"
            else -> "Bank Transfer"
        }
    }
}
