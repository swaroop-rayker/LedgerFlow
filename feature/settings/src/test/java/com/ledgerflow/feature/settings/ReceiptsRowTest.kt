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
     * **The promise it must not make.** Nothing in the app keeps a copy of a
     * receipt photograph: the CSV export carries metadata only, and no backup
     * has a trigger yet (`SPEC.md` §16 Q23). Advising an export as though it
     * preserved the images is the exact durability claim ADR-0019 forbids.
     */
    @Test
    fun theBody_neverSuggestsAnExportOrBackupKeepsThePhotos() {
        val body = deleteReceiptsBody(state(count = 3, bytes = 900_000))

        assertThat(body).doesNotContain("Export")
        assertThat(body).doesNotContain("export")
        assertThat(body).contains("aren't backed up")
        assertThat(body).contains("can't be recovered")
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
