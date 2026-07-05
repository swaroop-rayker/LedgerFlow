package com.ledgerflow.services.sms

import java.util.Calendar

object DateExtractor {
    private val dateRegexes = listOf(
        Regex("(\\d{2}-\\w{3})"),
        Regex("(\\d{2}/\\d{2}/\\d{2,4})"),
        Regex("(\\d{4}-\\d{2}-\\d{2})")
    )

    fun extract(normalizedSms: String): Long? {
        for (regex in dateRegexes) {
            val match = regex.find(normalizedSms)
            if (match != null) {
                val dateStr = match.groupValues[1]
                return parseDate(dateStr)
            }
        }
        return null
    }

    private fun parseDate(dateStr: String): Long? {
        try {
            val now = Calendar.getInstance()
            val year = now.get(Calendar.YEAR)
            
            if (dateStr.contains("-")) {
                val parts = dateStr.split("-")
                if (parts.size >= 2) {
                    val day = parts[0].toIntOrNull() ?: return null
                    val monthStr = parts[1].lowercase()
                    val month = when (monthStr) {
                        "jan" -> Calendar.JANUARY
                        "feb" -> Calendar.FEBRUARY
                        "mar" -> Calendar.MARCH
                        "apr" -> Calendar.APRIL
                        "may" -> Calendar.MAY
                        "jun" -> Calendar.JUNE
                        "jul" -> Calendar.JULY
                        "aug" -> Calendar.AUGUST
                        "sep" -> Calendar.SEPTEMBER
                        "oct" -> Calendar.OCTOBER
                        "nov" -> Calendar.NOVEMBER
                        "dec" -> Calendar.DECEMBER
                        else -> return null
                    }
                    val itemYear = if (parts.size == 3) parts[2].toIntOrNull() ?: year else year
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, itemYear)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, 12)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    return cal.timeInMillis
                }
            } else if (dateStr.contains("/")) {
                val parts = dateStr.split("/")
                if (parts.size >= 2) {
                    val day = parts[0].toIntOrNull() ?: return null
                    val month = (parts[1].toIntOrNull() ?: return null) - 1
                    val itemYear = if (parts.size == 3) {
                        val yr = parts[2].toIntOrNull() ?: year
                        if (yr < 100) 2000 + yr else yr
                    } else year
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, itemYear)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, 12)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    return cal.timeInMillis
                }
            }
        } catch (e: Exception) {
            // Safe fallback
        }
        return null
    }
}
