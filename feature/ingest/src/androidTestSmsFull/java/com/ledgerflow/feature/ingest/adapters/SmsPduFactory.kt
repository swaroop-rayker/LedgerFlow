package com.ledgerflow.feature.ingest.adapters

import java.io.ByteArrayOutputStream

/**
 * Builds real GSM SMS-DELIVER PDUs, so the SMS path can be exercised on a device
 * without a second phone.
 *
 * **This exists because there is no other way in.** `SmsMessage` has no public
 * constructor, so a JVM test cannot make one; and `adb shell am broadcast` is
 * refused for `SMS_RECEIVED` because `BROADCAST_SMS` is signature-level, so the
 * shell cannot deliver a synthetic message either. The only remaining route is
 * to hand the platform bytes it recognises.
 *
 * **The encoder validates itself.** The test asserts that what
 * `SmsMessage.createFromPdu` reads back equals what was encoded here. If this
 * builder is wrong, the platform's own parser disagrees and the test fails —
 * so a bug in the fixture cannot quietly become a passing test about nothing.
 *
 * Structure (3GPP TS 23.040 §9.2.2.1), in order:
 * SMSC length · first octet · originating address · PID · DCS · timestamp ·
 * user-data length · user data.
 */
internal object SmsPduFactory {

    /**
     * One complete message from [sender].
     *
     * [sender] may be alphanumeric (`VM-HDFCBK`) — which is what Indian banks
     * actually use, and the case worth testing — or a plain number.
     */
    fun deliver(sender: String, body: String): ByteArray {
        val out = ByteArrayOutputStream()

        // No SMSC: "use the default". Android accepts a zero-length SMSC field.
        out.write(0x00)
        // SMS-DELIVER, no more messages to send, no user-data header.
        out.write(0x04)
        out.write(encodeAddress(sender))
        // Protocol identifier: plain SMS.
        out.write(0x00)
        // Data coding scheme: GSM 7-bit default alphabet.
        //
        // Not 8-bit, which would be far easier to pack: Android treats an 8-bit
        // message as a *data* SMS and `getMessageBody()` comes back null, so the
        // receiver would correctly drop it and the test would prove nothing.
        out.write(0x00)
        out.write(TIMESTAMP)

        val septets = body.map(::septetFor)
        // User-data length is counted in septets, not bytes.
        out.write(septets.size)
        out.write(pack(septets))

        return out.toByteArray()
    }

    /**
     * The originating address.
     *
     * Two encodings, because the two kinds of sender are genuinely different:
     * a number is swapped-nibble BCD, and an alphanumeric sender ID is 7-bit
     * packed with type-of-address `0xD0`. Bank SMS in India is the second kind.
     */
    private fun encodeAddress(sender: String): ByteArray {
        val out = ByteArrayOutputStream()
        val numeric = sender.all { it.isDigit() }

        if (numeric) {
            // Length is the digit count; a trailing 'F' pads an odd number.
            out.write(sender.length)
            out.write(TOA_INTERNATIONAL)
            val padded = if (sender.length % 2 == 0) sender else sender + "F"
            padded.chunked(2).forEach { pair ->
                // Semi-octets arrive swapped: "91" is stored as 0x19.
                out.write(hexNibble(pair[1]) shl 4 or hexNibble(pair[0]))
            }
        } else {
            val packed = pack(sender.map(::septetFor))
            // For an alphanumeric address the length field counts *semi-octets*
            // of the packed value, which is twice its byte length. Android
            // divides back out to recover the character count.
            out.write(packed.size * 2)
            out.write(TOA_ALPHANUMERIC)
            out.write(packed)
        }
        return out.toByteArray()
    }

    /**
     * GSM 7-bit packing: seven-bit codes squeezed into eight-bit octets.
     *
     * Little-endian across the bit stream — each septet is shifted in above the
     * bits already buffered, and a whole octet is emitted whenever eight are
     * available.
     */
    private fun pack(septets: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        septets.forEach { septet ->
            buffer = buffer or (septet shl bits)
            bits += 7
            while (bits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bits -= 8
            }
        }
        if (bits > 0) out.write(buffer and 0xFF)
        return out.toByteArray()
    }

    /**
     * The GSM default alphabet agrees with ASCII across the characters a bank
     * message uses, so the fixtures stay in that range and this stays a check
     * rather than a translation table.
     */
    private fun septetFor(character: Char): Int {
        require(character.code in 0x20..0x7E) {
            "Fixture text must stay in the GSM/ASCII overlap; '$character' does not."
        }
        return character.code
    }

    private fun hexNibble(digit: Char): Int =
        if (digit == 'F') 0x0F else digit - '0'

    /** International number. */
    private const val TOA_INTERNATIONAL = 0x91

    /** Alphanumeric sender ID — what a bank's `VM-HDFCBK` is. */
    private const val TOA_ALPHANUMERIC = 0xD0

    /**
     * Service-centre timestamp, swapped BCD: 2026-08-26 11:00:00, +05:30.
     *
     * Fixed rather than "now", because a fixture that changes with the clock is
     * a fixture that fails on one machine and passes on another. Nothing under
     * test reads it — the receiver stamps `receivedAt` from the injected
     * [com.ledgerflow.core.common.time.Clock], which is the device's capture
     * time and deliberately not the network's.
     */
    private val TIMESTAMP = byteArrayOf(0x62, 0x80.toByte(), 0x62, 0x11, 0x00, 0x00, 0x22)
}
