package com.ledgerflow.services.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmsParserTest {

    // ==========================================
    // 1. DEBIT TRANSACTIONS (10 TESTS)
    // ==========================================

    @Test
    fun testDebit1() {
        val sms = "Your A/c XX1234 has been debited by Rs 1,500.50 on 04-Jul spent at AMAZON. Ref 987654"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(150050L, parsed.amountCents)
        assertEquals("AMAZON", parsed.merchantName)
        assertEquals("1234", parsed.accountNumber)
    }

    @Test
    fun testDebit2() {
        val sms = "INR 250.00 debited from card XX9988 at Swiggy"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(25000L, parsed.amountCents)
        assertEquals("Swiggy", parsed.merchantName)
        assertEquals("9988", parsed.accountNumber)
    }

    @Test
    fun testDebit3() {
        val sms = "₹1,200 spent on Zomato via UPI"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(120000L, parsed.amountCents)
        assertEquals("Zomato", parsed.merchantName)
        assertEquals("UPI", parsed.paymentMode)
    }

    @Test
    fun testDebit4() {
        val sms = "Purchase of Rs. 350 at IRCTC"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(35000L, parsed.amountCents)
        assertEquals("IRCTC", parsed.merchantName)
    }

    @Test
    fun testDebit5() {
        val sms = "POS Purchase of Rs 1,800.00 at Walmart using card ending 1234"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(180000L, parsed.amountCents)
        assertEquals("Walmart", parsed.merchantName)
        assertEquals("1234", parsed.accountNumber)
        assertEquals("POS", parsed.paymentMode)
    }

    @Test
    fun testDebit6() {
        val sms = "Fastag deduction of Rs 150.00 for vehicle MH12"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(15000L, parsed.amountCents)
    }

    @Test
    fun testDebit7() {
        val sms = "Rs.100 spent from Paytm Wallet"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(10000L, parsed.amountCents)
        assertEquals("Wallet", parsed.paymentMode)
    }

    @Test
    fun testDebit8() {
        val sms = "Debit Card purchase of Rs. 499 at Netflix"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(49900L, parsed.amountCents)
        assertEquals("Netflix", parsed.merchantName)
        assertEquals("Card", parsed.paymentMode)
    }

    @Test
    fun testDebit9() {
        val sms = "Paid Rs.50 to Local Store"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(5000L, parsed.amountCents)
        assertEquals("Local Store", parsed.merchantName)
    }

    @Test
    fun testDebit10() {
        val sms = "Sent Rs.200 to friend@upi"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(20000L, parsed.amountCents)
        assertEquals("friend@upi", parsed.merchantName)
    }

    // ==========================================
    // 2. CREDIT TRANSACTIONS (10 TESTS)
    // ==========================================

    @Test
    fun testCredit1() {
        val sms = "Your A/c XX1234 has been credited with Rs 10,000.00 on 04-Jul"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(1000000L, parsed.amountCents)
        assertEquals("1234", parsed.accountNumber)
    }

    @Test
    fun testCredit2() {
        val sms = "Salary Rs.75,000.00 credited to account XX5678"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(7500000L, parsed.amountCents)
        assertEquals("5678", parsed.accountNumber)
    }

    @Test
    fun testCredit3() {
        val sms = "Refund of Rs 599.00 credited by FLIPKART"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(59900L, parsed.amountCents)
        assertEquals("FLIPKART", parsed.merchantName)
    }

    @Test
    fun testCredit4() {
        val sms = "IMPS Credit of Rs. 5,000 from sender John"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(500000L, parsed.amountCents)
        assertEquals("IMPS", parsed.paymentMode)
    }

    @Test
    fun testCredit5() {
        val sms = "NEFT received Rs. 20,000 from employer"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(2000000L, parsed.amountCents)
        assertEquals("NEFT", parsed.paymentMode)
    }

    @Test
    fun testCredit6() {
        val sms = "RTGS received Rs. 1,00,000 on account XX1122"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(10000000L, parsed.amountCents)
        assertEquals("1122", parsed.accountNumber)
        assertEquals("RTGS", parsed.paymentMode)
    }

    @Test
    fun testCredit7() {
        val sms = "Cashback of Rs. 50 credited to your wallet"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(5000L, parsed.amountCents)
        assertEquals("Wallet", parsed.paymentMode)
    }

    @Test
    fun testCredit8() {
        val sms = "UPI received Rs. 500 from sender@okaxis"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(50000L, parsed.amountCents)
        assertEquals("UPI", parsed.paymentMode)
    }

    @Test
    fun testCredit9() {
        val sms = "Cash deposit of Rs. 10,000 in your account XX3456"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(1000000L, parsed.amountCents)
        assertEquals("3456", parsed.accountNumber)
        assertEquals("Cash Deposit", parsed.paymentMode)
    }

    @Test
    fun testCredit10() {
        val sms = "Interest of Rs. 234 credited to your account"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.CREDIT, parsed!!.type)
        assertEquals(23400L, parsed.amountCents)
    }

    // ==========================================
    // 3. TRANSFER & SELF TRANSFERS (10 TESTS)
    // ==========================================

    @Test
    fun testTransfer1() {
        val sms = "Rs.5000 debited from A/c XX1234 and credited to A/c XX5678"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(500000L, parsed.amountCents)
        assertEquals("1234", parsed.accountNumber)
        assertEquals("Self Transfer", parsed.paymentMode)
    }

    @Test
    fun testTransfer2() {
        val sms = "Rs.1000 transferred to your account from self"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(100000L, parsed.amountCents)
    }

    @Test
    fun testTransfer3() {
        val sms = "Rs.2500 transferred to self from Kotak bank"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(250000L, parsed.amountCents)
    }

    @Test
    fun testTransfer4() {
        val sms = "Own account transfer of Rs. 5,000 from SBI"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(500000L, parsed.amountCents)
    }

    @Test
    fun testTransfer5() {
        val sms = "Transfer to self of Rs.1500"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(150000L, parsed.amountCents)
    }

    @Test
    fun testTransfer6() {
        val sms = "Self transfer of Rs.10000 completed"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(1000000L, parsed.amountCents)
    }

    @Test
    fun testTransfer7() {
        val sms = "Bank transfer of Rs 25,000 from account XX1234"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(2500000L, parsed!!.amountCents)
        assertEquals("1234", parsed.accountNumber)
    }

    @Test
    fun testTransfer8() {
        val sms = "transferred Rs. 5000 to your other account"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.SELF_TRANSFER, parsed!!.type)
        assertEquals(500000L, parsed.amountCents)
    }

    @Test
    fun testTransfer9() {
        val sms = "ATM withdrawal of Rs. 5,000 from card XX12"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(500000L, parsed.amountCents)
        assertEquals("ATM", parsed.paymentMode)
    }

    @Test
    fun testTransfer10() {
        val sms = "Cash withdrawal of Rs.2000 from bank branch"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(TransactionType.DEBIT, parsed!!.type)
        assertEquals(200000L, parsed.amountCents)
        assertEquals("Cash Withdrawal", parsed.paymentMode)
    }

    // ==========================================
    // 4. MULTIPLE BANK FORMATS (10 TESTS)
    // ==========================================

    @Test
    fun testBankFormatHdfc() {
        val sms = "Alert: Rs 5,000 debited from HDFC Bank A/c XX3344 on 05-Jul"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(500000L, parsed!!.amountCents)
        assertEquals("3344", parsed.accountNumber)
    }

    @Test
    fun testBankFormatIcici() {
        val sms = "ICICI Bank Acct XX1122 debited by Rs 150.00. Info: UPI/Zomato"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(15000L, parsed!!.amountCents)
        assertEquals("1122", parsed.accountNumber)
        assertEquals("Zomato", parsed.merchantName)
    }

    @Test
    fun testBankFormatSbi() {
        val sms = "SBI A/c XX9988 debited by Rs 1,000.00 via UPI Ref 654321"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(100000L, parsed!!.amountCents)
        assertEquals("9988", parsed.accountNumber)
        assertEquals("654321", parsed.referenceNumber)
        assertEquals("UPI", parsed.paymentMode)
    }

    @Test
    fun testBankFormatAxis() {
        val sms = "Axis Bank Card XX5544 debited by INR 450.00 on 05/07/26 at AMAZON"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(45000L, parsed!!.amountCents)
        assertEquals("5544", parsed.accountNumber)
        assertEquals("AMAZON", parsed.merchantName)
    }

    @Test
    fun testBankFormatKotak() {
        val sms = "Kotak Bank A/c XX7766 credited with Rs 2,500.00 on interest"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(250000L, parsed!!.amountCents)
        assertEquals("7766", parsed.accountNumber)
        assertEquals(TransactionType.CREDIT, parsed.type)
    }

    @Test
    fun testBankFormatIdfc() {
        val sms = "IDFC FIRST Bank A/c XX8899 debited by Rs 500 for Fastag"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(50000L, parsed!!.amountCents)
        assertEquals("8899", parsed.accountNumber)
    }

    @Test
    fun testBankFormatFederal() {
        val sms = "Federal Bank A/c XX2211: Rs 1,200 debited. UPI Ref 9876"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(120000L, parsed!!.amountCents)
        assertEquals("2211", parsed.accountNumber)
        assertEquals("9876", parsed.referenceNumber)
    }

    @Test
    fun testBankFormatCanara() {
        val sms = "Canara Bank A/c XX4433: Rs. 3,000 credited via NEFT"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(300000L, parsed!!.amountCents)
        assertEquals("4433", parsed.accountNumber)
        assertEquals(TransactionType.CREDIT, parsed.type)
        assertEquals("NEFT", parsed.paymentMode)
    }

    @Test
    fun testBankFormatBob() {
        val sms = "Bank of Baroda A/c XX5566: Cash withdrawal Rs 5,000 at ATM"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(500000L, parsed!!.amountCents)
        assertEquals("5566", parsed.accountNumber)
        assertEquals("ATM", parsed.paymentMode)
    }

    @Test
    fun testBankFormatYes() {
        val sms = "Yes Bank A/c XX9900: UPI Transfer of Rs 1,000 to Swiggy"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(100000L, parsed!!.amountCents)
        assertEquals("9900", parsed.accountNumber)
        assertEquals("Swiggy", parsed.merchantName)
        assertEquals("UPI", parsed.paymentMode)
    }

    // ==========================================
    // 5. SPECIAL & EDGE CASES (10 TESTS)
    // ==========================================

    @Test
    fun testSpecialMalformedSms() {
        val sms = "malformed text without any amount or details"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNull(parsed)
    }

    @Test
    fun testSpecialSpamSms() {
        val sms = "Win cash up to Rs 50,000! Click here now to claim your reward."
        val parsed = SmsParser.parseToCandidate(sms)
        assertNull(parsed)
    }

    @Test
    fun testSpecialOtpSms() {
        val sms = "Your OTP for transaction of Rs 1,500.50 at Amazon is 123456. Do not share this with anyone."
        val parsed = SmsParser.parseToCandidate(sms)
        assertNull(parsed)
    }

    @Test
    fun testSpecialDupDetection() {
        val sms = "Alert: Rs 5,000 debited from SBI A/c XX3344 on 05-Jul"
        val parsed1 = SmsParser.parseToCandidate(sms)
        val parsed2 = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed1)
        assertNotNull(parsed2)
        assertEquals(parsed1!!.fingerprint, parsed2!!.fingerprint)
    }

    @Test
    fun testSpecialDupDetectionDiff() {
        val sms1 = "Alert: Rs 5,000 debited from SBI A/c XX3344 on 05-Jul"
        val sms2 = "Alert: Rs 5,000 debited from SBI A/c XX3344 on 06-Jul"
        val parsed1 = SmsParser.parseToCandidate(sms1)
        val parsed2 = SmsParser.parseToCandidate(sms2)
        assertNotNull(parsed1)
        assertNotNull(parsed2)
        assertNotEquals(parsed1!!.fingerprint, parsed2!!.fingerprint)
    }

    @Test
    fun testSpecialUnknownBankGeneral() {
        val sms = "Rs.350 spent on Uber"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(35000L, parsed!!.amountCents)
        assertEquals("Uber", parsed.merchantName)
    }

    @Test
    fun testSpecialLongSms() {
        val sms = "Thank you for banking with us. This is a secure system alert. Please do not share passwords. Your Account XX1234 has been debited by Rs.100 on 05-Jul."
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(10000L, parsed!!.amountCents)
        assertEquals("1234", parsed.accountNumber)
    }

    @Test
    fun testSpecialMalformedDecimalAmount() {
        val sms = "Rs 500.50.50 debited"
        // Regex should extract "500.50" or fail gracefully
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(50050L, parsed!!.amountCents)
    }

    @Test
    fun testSpecialZeroAmount() {
        val sms = "Rs 0 debited from account"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNull(parsed)
    }

    @Test
    fun testSpecialCaseInsensitivity() {
        val sms = "SPENT RS.100 ON AMZN"
        val parsed = SmsParser.parseToCandidate(sms)
        assertNotNull(parsed)
        assertEquals(10000L, parsed!!.amountCents)
        assertEquals("AMZN", parsed.merchantName)
    }
}
