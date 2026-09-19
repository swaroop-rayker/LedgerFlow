package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.page
import com.ledgerflow.feature.ocr.extraction.ReceiptFixtures.row
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Item 7a (owner, 2026-09-19): a digital PDF whose text names no shop has its
 * rendered header recognised, and the header's **legal entity** is taken — the
 * first column cell that begins with a name ending in a legal suffix, cut after
 * the suffix. The shapes here are bigbasket's and Zepto's as measured on the
 * device; the rest are synthetic.
 */
class MerchantFallbackTest {

    /** bigbasket as ML Kit reads it: logo tagline, name, address, in one row. */
    private val bigbasketHeader = page(
        row(0, 600f to "Original Tax Invoice"),
        row(1, 100f to "basket"),
        row(
            2,
            40f to "ATATA Enterprise",
            300f to "Innovative Retail Concepts Pvt Ltd,71 2",
            900f to "Karunakar Rayker,38, 1st Floor, Block",
        ),
        row(3, 300f to "Annana Mane SM Kalyana mantapa"),
    )

    @Test
    fun bigbasket_theNameIsCutAtItsSuffix_andTheTaglineAndAddressStayOut() {
        assertThat(ReceiptExtractor.legalEntityName(bigbasketHeader)).isEqualTo("Innovative Retail Concepts Pvt Ltd")
    }

    @Test
    fun zepto_aLabelledPrivateLimited_losesItsLabel() {
        val header = page(row(0, 100f to "Seller Name: Geddit Convenience Private Limited"))
        assertThat(ReceiptExtractor.legalEntityName(header)).isEqualTo("Geddit Convenience Private Limited")
    }

    /** The suffix is a whole word: `Unlimited` is not `Limited`, `Ltd Groceries` names no entity. */
    @Test
    fun theSuffixIsAWholeWordThatEndsTheName() {
        assertThat(ReceiptExtractor.legalEntityName(page(row(0, 100f to "Unlimited Fashion Store")))).isNull()
        assertThat(ReceiptExtractor.legalEntityName(page(row(0, 100f to "Ltd Groceries")))).isNull()
        assertThat(ReceiptExtractor.legalEntityName(page(row(0, 100f to "Sri Balaji Stores LLP"))))
            .isEqualTo("Sri Balaji Stores LLP")
    }

    /** The first suffix ends the name: a second entity later in the cell is not part of it. */
    @Test
    fun theFirstSuffixEndsTheName() {
        val header = page(row(0, 100f to "ABC Traders Pvt Ltd, a unit of XYZ Limited"))
        assertThat(ReceiptExtractor.legalEntityName(header)).isEqualTo("ABC Traders Pvt Ltd")
    }

    /**
     * A name may start with a digit, and a bullet before it is not part of it.
     * Both were refused by an earlier version anchored on a letter — found by
     * the mutation sweep, when removing the anchor reddened nothing.
     */
    @Test
    fun aNameStartingWithADigit_orAfterABullet_isRead() {
        assertThat(ReceiptExtractor.legalEntityName(page(row(0, 100f to "24X7 Stores Pvt Ltd"))))
            .isEqualTo("24X7 Stores Pvt Ltd")
        assertThat(ReceiptExtractor.legalEntityName(page(row(0, 100f to "** Sri Balaji Stores LLP"))))
            .isEqualTo("Sri Balaji Stores LLP")
    }

    /** A registration row mentioning an entity is not a shop. */
    @Test
    fun anIdentifierRow_isNotAName() {
        val registration = page(row(0, 100f to "GSTIN: 29AAACI1195H1ZK Innovative Retail Pvt Ltd"))
        assertThat(ReceiptExtractor.legalEntityName(registration)).isNull()
    }

    @Test
    fun noEntityInTheHeader_givesNothing() {
        val header = page(row(0, 100f to "Details of Supplier"), row(1, 100f to "basket"))
        assertThat(ReceiptExtractor.legalEntityName(header)).isNull()
    }

    // ── Only when the text named no shop ──────────────────────────────────

    @Test
    fun aMerchantFromTheText_isKept_andNothingIsRecognised() = runTest {
        var recognised = 0
        val fromText = ExtractedTransaction(merchantRaw = "Geddit Convenience Private Limited")
        val kept = MerchantFallback.apply(fromText, "INR") {
            recognised++
            bigbasketHeader
        }
        assertThat(kept.merchantRaw).isEqualTo("Geddit Convenience Private Limited")
        assertThat(recognised).isEqualTo(0)
    }

    @Test
    fun noMerchantFromTheText_takesTheRecognisedHeadersEntity() = runTest {
        val filled = MerchantFallback.apply(ExtractedTransaction(merchantRaw = null), "INR") { bigbasketHeader }
        assertThat(filled.merchantRaw).isEqualTo("Innovative Retail Concepts Pvt Ltd")
    }

    /** Recognition failing, or finding no entity, leaves the merchant for the user as before. */
    @Test
    fun nothingRecognised_leavesItEmpty() = runTest {
        assertThat(MerchantFallback.apply(ExtractedTransaction(), "INR") { null }.merchantRaw).isNull()
    }
}
