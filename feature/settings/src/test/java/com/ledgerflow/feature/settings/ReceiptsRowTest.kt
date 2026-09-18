package com.ledgerflow.feature.settings

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import org.junit.Test

/**
 * The "Receipts" row and its delete dialog (ADR-0023).
 *
 * The row is the ADR's whole answer to unbounded growth — show the number
 * rather than delete on a timer — so the subtitle's figures are the substance,
 * not decoration. The dialog guards the only irreversible action on receipt
 * images, and **what it promises is the load-bearing part**: it used to tell
 * the user to "export first if you want to keep them", which kept no
 * photograph, because the export is CSV and carries metadata only.
 */
class ReceiptsRowTest {

    private fun state(count: Int, bytes: Long, loaded: Boolean = true) =
        MoreUiState(receipts = AttachmentUsage(count = count, bytes = bytes), isLoaded = loaded)

    // ─── The row ────────────────────────────────────────────────────────────

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
        assertThat(receiptsSubtitle(state(count = 1, bytes = 250_000)))
            .isEqualTo("1 image, 250 kB. Tap to delete.")
    }

    @Test
    fun severalImages_readAsPluralWithTheirSize() {
        assertThat(receiptsSubtitle(state(count = 12, bytes = 3_400_000)))
            .isEqualTo("12 images, 3 MB. Tap to delete.")
    }

    // ─── The delete dialog ──────────────────────────────────────────────────

    @Test
    fun theTitle_namesTheCount() {
        assertThat(deleteReceiptsTitle(state(count = 1, bytes = 1))).isEqualTo("Delete 1 receipt image?")
        assertThat(deleteReceiptsTitle(state(count = 7, bytes = 1))).isEqualTo("Delete 7 receipt images?")
    }

    /**
     * **The promises it must not make.** The CSV export carries no photograph,
     * so it must not be offered as a way to keep them. And although "Back up
     * now" copies the photos into the backup folder, nothing in the app
     * restores yet (§16 Q11), so the dialog must not promise recovery either —
     * only what is true: this phone keeps no other copy, and a backup's copies
     * stay in its folder.
     */
    @Test
    fun theBody_promisesNeitherAnExportNorARestore() {
        val body = deleteReceiptsBody(state(count = 3, bytes = 900_000))

        assertThat(body).doesNotContain("Export")
        assertThat(body).doesNotContain("export")
        assertThat(body.lowercase()).doesNotContain("restore")
        assertThat(body).contains("keeps no other copy")
        assertThat(body).contains("stay in that backup folder")
    }

    /** Irreversible, said in those words; and the entries survive. */
    @Test
    fun theBody_saysItIsIrreversibleAndThatEntriesSurvive() {
        val body = deleteReceiptsBody(state(count = 3, bytes = 900_000))

        assertThat(body).contains("cannot be undone")
        assertThat(body).contains("entries and their amounts are untouched")
        assertThat(body).contains("900 kB")
    }
}
