package com.ledgerflow.core.ui.phrase

/**
 * Which decoded codes the scanner hands to its screen.
 *
 * **Every distinct code, each once.** The first version latched on the first
 * code of any kind and then went quiet. A foreign QR code — a Wi-Fi sticker, a
 * receipt's payment code — is answered by the screen with "keep looking", so the
 * camera stayed open and never delivered again: pointing it at the real kit
 * afterwards did nothing (§8 BUG34). The screen, not the scanner, knows what is
 * ours (`PhraseQr` lives in `:core:domain`, which this module does not see), so
 * the scanner's only job is not to repeat itself: the same code seen in thirty
 * frames a second is one delivery.
 *
 * Called from the analyser thread; synchronised because nothing guarantees
 * CameraX keeps one thread.
 */
internal class ScanDeliveries {
    private var last: String? = null

    /** Whether [text] should be delivered: true once per change of code. */
    @Synchronized
    fun shouldDeliver(text: String): Boolean {
        if (text == last) return false
        last = text
        return true
    }
}
