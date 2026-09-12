package com.ledgerflow.feature.ocr.extraction

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.text.JaroWinkler
import org.junit.Test

/**
 * [ReceiptKeywords.matches] — the fuzzy branch, and mostly the things it must
 * **not** match.
 *
 * The recall case is one test. The rest of this file is precision, because that
 * is where the asymmetry is: a missed keyword costs one line the user retypes,
 * while a keyword matched that was never there silently deletes a purchase from
 * the bill — or, in `REFUND`'s case, reverses the direction of the whole
 * receipt. §12 concedes a tolerance on item *names* and refuses one on money;
 * this sits between the two, and the three clauses below are what earn it.
 *
 * Every near-miss here is a real Indian retail item name, and every one of them
 * was **measured** as a false positive under a weaker rule rather than imagined:
 * the sweep behind this file compared four candidate rules over a vocabulary of
 * ~75 such names, and the three-clause rule is the only one that admitted zero.
 */
class ReceiptKeywordMatchTest {

    // ─── Recall: the case this was built for ────────────────────────────────

    /**
     * **The bug.** `S GST 9%` came back from ML Kit as `S 6ST 9%`, exact
     * substring missed, and the row fell through as a line item — one of the two
     * "items" that reached the Inbox on a six-item bill.
     */
    @Test
    fun matches_aMisreadGstRow_isStillTax() {
        assertThat(ReceiptKeywords.matches("S 6ST 9% 5.34", ReceiptKeywords.TAX)).isTrue()
        assertThat(ReceiptKeywords.matches("C 6ST 9% 5.34", ReceiptKeywords.TAX)).isTrue()
    }

    /** And the classifier acts on it, rather than the matcher merely saying yes. */
    @Test
    fun classify_aMisreadGstRow_isNotAnItem() {
        val kinds = ReceiptLineClassifier.classify(
            listOf(
                ClassifiableRow("CRISPS 95G", "CRISPS 95G", hasAmount = true),
                ClassifiableRow("S 6ST 9% 5.34", "S 6ST 9%", hasAmount = true),
                ClassifiableRow("CRISPS 177G", "CRISPS 177G", hasAmount = true),
            ),
        )

        assertThat(kinds[1]).isEqualTo(ReceiptLineKind.TAX)
        // And it must not end the item block -- the per-item-tax rule.
        assertThat(kinds[2]).isEqualTo(ReceiptLineKind.ITEM)
    }

    /**
     * The correctly-read row still works. Stated because a fuzzy branch that
     * accidentally replaced the exact one would pass every test above.
     */
    @Test
    fun matches_aCorrectlyReadGstRow_isStillTax() {
        assertThat(ReceiptKeywords.matches("S GST 9% 5.34", ReceiptKeywords.TAX)).isTrue()
        assertThat(ReceiptKeywords.matches("CGST 2.5% 17.73", ReceiptKeywords.TAX)).isTrue()
    }

    // ─── Precision: the equal-length clause ─────────────────────────────────

    /**
     * **The worst one available, and the reason equal length is a clause rather
     * than a nicety.** `REFUND` is not a line classifier — `ReceiptExtractor`
     * uses it to decide whether the whole receipt is a DEBIT or a CREDIT. A
     * bottle of refined oil scoring as a refund would turn a purchase into
     * income.
     */
    @Test
    fun matches_refinedOil_isNotARefund() {
        assertThat(ReceiptKeywords.matches("REFINED OIL 1L 180.00", ReceiptKeywords.REFUND))
            .isFalse()

        // And the score is over the threshold, so length is demonstrably the
        // only thing standing in the way.
        assertThat(JaroWinkler.similarity("REFINED", "REFUND"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    /**
     * A dropped or inserted character is not a substituted glyph, and the other
     * four measured cases are all that shape.
     */
    @Test
    fun matches_itemsOneCharacterShorterThanAKeyword_doNotMatch() {
        // TIL (sesame) vs TILL, at 0.9417 -- the highest-scoring false positive
        // the sweep found anywhere.
        assertThat(ReceiptKeywords.matches("TIL OIL 1L 220.00", ReceiptKeywords.ADMIN)).isFalse()
        // PAD vs PAID, 0.9333
        assertThat(ReceiptKeywords.matches("PAD WHISPER 8N 75.00", ReceiptKeywords.TENDER))
            .isFalse()
        // CHANA vs CHANGE, 0.8933 -- the same score as the true positive above.
        assertThat(ReceiptKeywords.matches("CHANA 500G 60.00", ReceiptKeywords.TENDER)).isFalse()

        assertThat(JaroWinkler.similarity("TIL", "TILL"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
        assertThat(JaroWinkler.similarity("CHANA", "CHANGE"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    // ─── Precision: the one-differing-character clause ──────────────────────

    /**
     * `CASHEWS` and `CASHIER` are the same length and differ in **three**
     * positions, but share a four-character prefix — and the Winkler boost
     * carries that over 0.88. Equal length alone does not catch it; this is the
     * single case that needs the second clause.
     */
    @Test
    fun matches_cashews_isNotACashier() {
        assertThat(ReceiptKeywords.matches("CASHEWS W240 450.00", ReceiptKeywords.ADMIN)).isFalse()

        assertThat("CASHEWS".length).isEqualTo("CASHIER".length)
        assertThat(JaroWinkler.similarity("CASHEWS", "CASHIER"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    /** Same shape, on a word the exact layer also has no opinion about. */
    @Test
    fun matches_aChangeMakerToy_isNotAnExchange() {
        assertThat(ReceiptKeywords.matches("CHANGE MAKER TOY 99.00", ReceiptKeywords.ADMIN))
            .isFalse()
    }

    // ─── Precision: the threshold clause ────────────────────────────────────

    /**
     * **Twelve of the measured false positives were three-character keywords**,
     * and every one of them differs from a real item name in exactly one
     * position. `TEA` against `TEL` is the one that matters most: tea is on
     * every Indian grocery bill, `TEL` is in the ADMIN set, and a match would
     * make the row administrative and drop it.
     *
     * Equal length and one differing character are both satisfied here. The
     * threshold is the only clause doing any work, which is the quantitative
     * form of "a short keyword carries less evidence".
     */
    @Test
    fun matches_threeCharacterItemNames_doNotMatchThreeCharacterKeywords() {
        val mustNotMatch = listOf(
            "TEA 250G 120.00" to ReceiptKeywords.ADMIN, // TEA ~ TEL
            "TIL OIL 1L 220.00" to ReceiptKeywords.ADMIN, // TIL ~ TIN
            "CAN COKE 330ML 40.00" to ReceiptKeywords.ADMIN, // CAN ~ CIN, PAN
            "PEN BLUE 10.00" to ReceiptKeywords.ADMIN, // PEN ~ PAN
            "FAN TABLE 400MM 1250.00" to ReceiptKeywords.ADMIN, // FAN ~ PAN
            "BAT CRICKET 899.00" to ReceiptKeywords.TAX, // BAT ~ VAT
            "MAT DOOR 199.00" to ReceiptKeywords.TAX, // MAT ~ VAT
            "CURD 400G 45.00" to ReceiptKeywords.TENDER, // CURD ~ CARD
        )

        mustNotMatch.forEach { (row, keywords) ->
            assertThat(ReceiptKeywords.matches(row, keywords)).isFalse()
        }

        // One differing character in each, so the threshold is what refuses them.
        assertThat(JaroWinkler.similarity("TEA", "TEL"))
            .isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
        assertThat(JaroWinkler.similarity("BAT", "VAT"))
            .isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
        assertThat(JaroWinkler.similarity("CURD", "CARD"))
            .isLessThan(JaroWinkler.MERCHANT_THRESHOLD)
    }

    /**
     * The bare three-character `GST` stays exact-only, which is the same fact
     * from the other side: the recall case above works because the TAX set
     * lists `S GST`, not because 0.88 was bent.
     */
    @Test
    fun matches_aMisreadBareGst_doesNotMatch() {
        assertThat(ReceiptKeywords.matches("6ST 9% 5.34", ReceiptKeywords.TAX)).isFalse()
    }

    // ─── The ordering traps, from the matcher's side ────────────────────────

    /**
     * The three traps `ReceiptKeywords`' KDoc names, asserted here as well as
     * in [ReceiptLineClassifierTest] and [GstTaxInvoiceTest].
     *
     * Not redundant: those two test the *classifier's* ordering, and this tests
     * that the fuzzy layer did not quietly widen the sets it orders. A row that
     * now matches one set *earlier* than before is the failure mode a
     * both-layers matcher has and a substring-only one does not.
     */
    @Test
    fun matches_theOrderingTraps_areUnchanged() {
        // SUB TOTAL matches SUBTOTAL and TOTAL; the classifier's order decides.
        assertThat(ReceiptKeywords.matches("SUB TOTAL 709.00", ReceiptKeywords.SUBTOTAL)).isTrue()
        // ... and must NOT have acquired a DISCOUNT match, which is tried first.
        assertThat(ReceiptKeywords.matches("SUB TOTAL 709.00", ReceiptKeywords.DISCOUNT)).isFalse()

        assertThat(ReceiptKeywords.matches("TOTAL SAVING: 75.00", ReceiptKeywords.ADMIN)).isTrue()
        assertThat(ReceiptKeywords.matches("TOTAL QTY: 14", ReceiptKeywords.ADMIN)).isTrue()

        // Nor may an item row have acquired one.
        listOf(
            ReceiptKeywords.DISCOUNT,
            ReceiptKeywords.SUBTOTAL,
            ReceiptKeywords.TAX,
            ReceiptKeywords.TOTAL,
            ReceiptKeywords.TENDER,
            ReceiptKeywords.ADMIN,
            ReceiptKeywords.REFUND,
        ).forEach { keywords ->
            assertThat(ReceiptKeywords.matches("TOOR DAL 1KG 330.00", keywords)).isFalse()
            assertThat(ReceiptKeywords.matches("SHOWERGEL 250ML 174.00", keywords)).isFalse()
        }
    }

    // ─── Tokenisation ───────────────────────────────────────────────────────

    /**
     * Devanagari survives the word split, matras included.
     *
     * The split is on whitespace and **ASCII** punctuation only, and that
     * restriction is the load-bearing part: `राशि` is a consonant plus a
     * combining matra, so a "not a letter or digit" split would have discarded
     * the matras and left `रश`. The two Devanagari entries in these sets would
     * then have behaved differently from every Latin one, silently.
     */
    @Test
    fun matches_devanagari_survivesTheWordSplit() {
        assertThat(ReceiptKeywords.matches("बिल राशि 500.00", ReceiptKeywords.TOTAL)).isTrue()
        // A misread matra is one differing character on an eight-unit string,
        // so the same rule that admits `S 6ST` admits this.
        assertThat(ReceiptKeywords.matches("बिल राशी 500.00", ReceiptKeywords.TOTAL)).isTrue()
        // And an unrelated Devanagari word still does not match.
        assertThat(ReceiptKeywords.matches("धन्यवाद", ReceiptKeywords.TOTAL)).isFalse()
    }

    /**
     * Punctuation inside a keyword is a word boundary on both sides, so
     * `ROUND-OFF` reaches `ROUND OFF` as a two-token window.
     *
     * Both spellings are already listed, so this is not new recall — it is the
     * tokeniser's behaviour pinned, because the same rule is what lets
     * `TOTAL QTY:` reach `TOTAL QTY`.
     */
    @Test
    fun matches_punctuationIsAWordBoundary() {
        assertThat(ReceiptKeywords.matches("ROUND-OFF 0.46", ReceiptKeywords.TENDER)).isTrue()
        assertThat(ReceiptKeywords.matches("SUB-TOTAL 709.00", ReceiptKeywords.SUBTOTAL)).isTrue()
    }

    /**
     * **The exact-substring layer is not redundant, and this is the only test
     * that says so.**
     *
     * Added because a mutation sweep deleted that layer outright and **nothing
     * went red** — the fuzzy layer matches a whole-word keyword at 1.0, so it
     * silently covered every case the suite had. What it cannot cover is a
     * keyword that is a *part* of a word, and those are real:
     *
     * - `THANK` inside `THANKS`, which is on the foot of most bills. A window
     *   match refuses it, correctly, because the lengths differ.
     * - `GSTIN` glued to its number, which ML Kit returns as one run more often
     *   than not.
     *
     * Without this test a later "simplification" to fuzzy-only would compile,
     * stay green, and quietly stop recognising the entire ADMIN set's
     * inflections.
     */
    @Test
    fun matches_aKeywordInsideALongerWord_stillMatches() {
        assertThat(ReceiptKeywords.matches("THANKS FOR SHOPPING", ReceiptKeywords.ADMIN)).isTrue()
        assertThat(ReceiptKeywords.matches("GSTIN29AAACT2727Q1ZW", ReceiptKeywords.ADMIN)).isTrue()

        // And the fuzzy layer genuinely does not reach them, so the assertion
        // above is about the exact layer rather than about either.
        assertThat(JaroWinkler.similarity("THANKS", "THANK"))
            .isGreaterThan(JaroWinkler.MERCHANT_THRESHOLD)
        assertThat("THANKS".length).isNotEqualTo("THANK".length)
    }

    /** An empty or amount-only row matches nothing. */
    @Test
    fun matches_aRowWithNoWords_matchesNothing() {
        assertThat(ReceiptKeywords.matches("", ReceiptKeywords.TOTAL)).isFalse()
        assertThat(ReceiptKeywords.matches("   ", ReceiptKeywords.TOTAL)).isFalse()
        assertThat(ReceiptKeywords.matches("614.00", ReceiptKeywords.TOTAL)).isFalse()
    }
}
