package com.ledgerflow.core.ui.phrase

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test

/**
 * The scanner's two pieces of logic that do not need a camera (ADR-0028):
 * which codes it hands over, and how a camera frame becomes something ZXing
 * can read. The public BIP-39 test phrase throughout, never a real one.
 */
class ScanFramesTest {

    /** BUG34: a foreign code first must not silence the scanner for the kit. */
    @Test
    fun Bug34_aForeignCodeFirst_theKitIsStillDelivered() {
        val deliveries = ScanDeliveries()

        assertThat(deliveries.shouldDeliver("WIFI:S:cafe;T:WPA;P:hunter2;;")).isTrue()
        assertThat(deliveries.shouldDeliver(KIT)).isTrue()
    }

    /** Thirty frames a second of the same page are one delivery, not thirty. */
    @Test
    fun theSameCodeInEveryFrame_isDeliveredOnce() {
        val deliveries = ScanDeliveries()

        val delivered = List(30) { deliveries.shouldDeliver(KIT) }.count { it }

        assertThat(delivered).isEqualTo(1)
    }

    /** Back to a code after another one is a change, and is delivered again. */
    @Test
    fun aCodeSeenAgainAfterAnother_isDeliveredAgain() {
        val deliveries = ScanDeliveries()
        deliveries.shouldDeliver(KIT)
        deliveries.shouldDeliver("https://example.com")

        assertThat(deliveries.shouldDeliver(KIT)).isTrue()
    }

    /**
     * A padded camera plane with its last row's padding omitted, as many
     * sensors deliver it, decodes to the kit's words exactly as the scanner
     * hands it over. Pinned because the obvious "fix" -- padding the buffer --
     * was once proposed for a failure this shows does not exist.
     */
    @Test
    fun aPaddedPlaneWithAShortLastRow_decodesTheKitAsIs() {
        val frame = cameraFrame(KIT, width = 400, height = 400, rowStride = 448)
        assertThat(frame.bytes.size).isLessThan(frame.rowStride * frame.height)

        assertThat(decode(frame, frame.bytes)).isEqualTo(KIT)
    }

    @Test
    fun anUnpaddedPlane_decodesTheKit() {
        val frame = cameraFrame(KIT, width = 400, height = 400, rowStride = 400)

        assertThat(decode(frame, frame.bytes)).isEqualTo(KIT)
    }

    private class Frame(val bytes: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /** [text] as a QR code in a Y plane of [rowStride], the last row cut to [width]. */
    private fun cameraFrame(text: String, width: Int, height: Int, rowStride: Int): Frame {
        val hints = mapOf(EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints)
        val bytes = ByteArray(rowStride * (height - 1) + width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * rowStride + x] = if (matrix[x, y]) INK else PAPER
            }
        }
        return Frame(bytes, width, height, rowStride)
    }

    private fun decode(frame: Frame, plane: ByteArray): String {
        val source = PlanarYUVLuminanceSource(
            plane,
            frame.rowStride,
            frame.height,
            0,
            0,
            frame.width,
            frame.height,
            false,
        )
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {
        /** `LFBK1:` and the public BIP-39 test vector — valid, and nobody's key. */
        val KIT = "LFBK1:" + (List(23) { "abandon" } + "art").joinToString(" ")
        const val INK: Byte = 0x10
        const val PAPER: Byte = 0xE0.toByte()
    }
}
