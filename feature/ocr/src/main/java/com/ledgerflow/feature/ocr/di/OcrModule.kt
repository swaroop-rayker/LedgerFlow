package com.ledgerflow.feature.ocr.di

import com.ledgerflow.feature.ocr.recognition.MlKitReceiptTextRecognizer
import com.ledgerflow.feature.ocr.recognition.ReceiptTextRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * OCR's one binding (SPEC.md §5.3, ADR-0021).
 *
 * **The interface is the seam ADR-0021 records as cheap to reverse at.** That
 * decision — bundled ML Kit, with the `INTERNET` permission it drags in
 * accepted — is written down as close and reopenable, and a recogniser reachable
 * only through [ReceiptTextRecognizer] is one that can be swapped for another
 * engine without touching the extraction pipeline or the capture screen. One
 * line here is what keeps that true.
 *
 * `@Singleton` because [MlKitReceiptTextRecognizer] holds two ML Kit clients,
 * each of which loads a bundled model on first use. Per-capture construction
 * would pay that on every receipt.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class OcrModule {

    @Binds
    @Singleton
    internal abstract fun bindReceiptTextRecognizer(
        recognizer: MlKitReceiptTextRecognizer,
    ): ReceiptTextRecognizer
}
