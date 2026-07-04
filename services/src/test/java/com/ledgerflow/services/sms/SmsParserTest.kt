package com.ledgerflow.services.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SmsParserTest {

    @Test
    fun testDebitSmsParsing() {
        val sms = "Your A/c XX1234 has been debited by Rs 1,500.50 on 04-Jul spent at AMAZON. Ref 987654"
        val parsed = SmsParser.parse(sms)
        assertNotNull(parsed)
        assertEquals(150050L, parsed!!.amountCents)
        assertEquals("AMAZON. Ref 987654", parsed.merchantName)
        assertEquals("A/c XX1234", parsed.paymentMethod)
    }

    @Test
    fun testUpiSmsParsing() {
        val sms = "Spent Rs.250 on SWIGGY using UPI. Ref 12345678"
        val parsed = SmsParser.parse(sms)
        assertNotNull(parsed)
        assertEquals(25000L, parsed!!.amountCents)
        assertEquals("SWIGGY", parsed.merchantName)
        assertEquals("UPI", parsed.paymentMethod)
    }

    @Test
    fun testOtpSmsIgnored() {
        val sms = "Your OTP for transaction of Rs 1,500.50 at Amazon is 123456. Do not share this with anyone."
        val parsed = SmsParser.parse(sms)
        assertNull(parsed)
    }

    @Test
    fun testSpamSmsIgnored() {
        val sms = "Win cash up to Rs 50,000! Click here now to claim your reward."
        val parsed = SmsParser.parse(sms)
        assertNull(parsed)
    }
}
