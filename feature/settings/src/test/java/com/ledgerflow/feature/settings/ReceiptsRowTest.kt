package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import org.junit.Test

/**
 * The "Receipts" row (ADR-0023, V2-5).
 *
 * The row makes growth visible, so the subtitle's figures are the substance.
 * Since V2-5 it opens the Receipts page, where deleting is; its dialogs are
 * tested there (`ReceiptsTextTest`).
 */
class ReceiptsRowTest {

    private fun state(count: Int, bytes: Long, loaded: Boolean = true) =
        SettingsUiState(receipts = AttachmentUsage(count = count, bytes = bytes), isLoaded = loaded)

    @Test
    fun beforeTheUsageArrives_saysWhatTheRowIsForRatherThanGuessing() {
        val subtitle = receiptsSubtitle(state(count = 0, bytes = 0, loaded = false))

        // "No images yet" would be a claim before anything was measured.
        assertThat(subtitle).doesNotContain("No images")
    }

    @Test
    fun noImages_explainsWhatReceiptsAre() {
        assertThat(receiptsSubtitle(state(count = 0, bytes = 0)))
            .isEqualTo("No images yet. Receipts you scan are kept here, encrypted.")
    }

    @Test
    fun oneImage_readsAsSingularWithItsSize() {
        assertThat(receiptsSubtitle(state(count = 1, bytes = 250_000))).isEqualTo("1 image, 250 kB")
    }

    /** The row opens a page now: it no longer says "Tap to delete". */
    @Test
    fun severalImages_readAsPluralWithTheirSize() {
        assertThat(receiptsSubtitle(state(count = 12, bytes = 3_400_000))).isEqualTo("12 images, 3 MB")
    }
}
