package com.ledgerflow.core.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyUtilsTest {

    @Test
    fun testDoubleToCents() {
        assertEquals(1050L, CurrencyUtils.doubleToCents(10.50))
        assertEquals(100L, CurrencyUtils.doubleToCents(1.00))
        assertEquals(0L, CurrencyUtils.doubleToCents(0.0))
        assertEquals(123456L, CurrencyUtils.doubleToCents(1234.56))
    }

    @Test
    fun testCentsToDouble() {
        assertEquals(10.50, CurrencyUtils.centsToDouble(1050L), 0.0)
        assertEquals(1.00, CurrencyUtils.centsToDouble(100L), 0.0)
        assertEquals(0.0, CurrencyUtils.centsToDouble(0L), 0.0)
        assertEquals(1234.56, CurrencyUtils.centsToDouble(123456L), 0.0)
    }
}
