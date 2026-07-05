package com.ledgerflow.services.sms

object SmsNormalizer {
    fun normalize(sms: String): String {
        // Keep the original case but normalize newlines, tabs, and duplicate spaces
        var text = sms.replace(Regex("[\\r\\n\\t]+"), " ")
        text = text.replace(Regex("\\s+"), " ")
        return text.trim()
    }
}
