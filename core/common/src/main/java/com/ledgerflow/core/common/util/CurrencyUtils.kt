package com.ledgerflow.core.common.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object CurrencyUtils {
    /**
     * Converts a double amount (e.g. 10.50) to long cents (e.g. 1050)
     */
    fun doubleToCents(amount: Double): Long {
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_EVEN)
            .multiply(BigDecimal.valueOf(100))
            .toLong()
    }

    /**
     * Converts long cents (e.g. 1050) to a double amount (e.g. 10.50)
     */
    fun centsToDouble(cents: Long): Double {
        return BigDecimal.valueOf(cents)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN)
            .toDouble()
    }

    /**
     * Formats a cent amount into a localized currency string (e.g. $10.50 or ₹10.50)
     */
    fun formatCents(cents: Long, locale: Locale = Locale.getDefault()): String {
        val formatter = NumberFormat.getCurrencyInstance(locale)
        val amount = centsToDouble(cents)
        return formatter.format(amount)
    }
}
