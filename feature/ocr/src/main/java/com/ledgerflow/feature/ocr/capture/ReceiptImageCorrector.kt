package com.ledgerflow.feature.ocr.capture

import android.graphics.Bitmap

/**
 * Makes a photographed receipt easier to read before recognition, or returns it
 * unchanged. Never worse: an implementation that cannot tell what to do returns
 * its input. The seam ADR-0024's OpenCV decision would be reversed at.
 */
public interface ReceiptImageCorrector {
    public fun correct(bitmap: Bitmap): Bitmap
}
