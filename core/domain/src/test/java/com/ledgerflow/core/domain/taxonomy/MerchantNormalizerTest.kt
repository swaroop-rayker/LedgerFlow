package com.ledgerflow.core.domain.taxonomy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `merchant.normalized_key` is `UNIQUE`, so this function *is* the definition of
 * "the same merchant".
 *
 * Two properties are being defended, and they pull in opposite directions:
 * variants of one shop must collapse onto one key, and genuinely different shops
 * must not. The second matters more — a duplicate merchant is one tap to merge,
 * while a wrong merge silently blends two histories and there is no undo.
 */
class MerchantNormalizerTest {

    private fun normalize(raw: String) = MerchantNormalizer.normalize(raw)

    @Test
    fun caseAndSurroundingSpaceDoNotMatter() {
        assertThat(normalize("  Swiggy ")).isEqualTo(normalize("SWIGGY"))
    }

    @Test
    fun punctuationIsCollapsedToSingleSpaces() {
        assertThat(normalize("Big-Bazaar   #2")).isEqualTo("big bazaar")
    }

    @Test
    fun diacriticsAreFolded() {
        assertThat(normalize("Café Coffee Day")).isEqualTo("cafe coffee day")
    }

    /** UPI and card descriptors glue a reference code on after a '*'. */
    @Test
    fun referenceCodesAfterAnAsteriskAreDropped() {
        assertThat(normalize("SWIGGY*ORDER4821")).isEqualTo("swiggy")
        assertThat(normalize("AMAZON IN*8QW12")).isEqualTo("amazon in")
    }

    @Test
    fun railPrefixesAreStripped() {
        assertThat(normalize("UPI/Zomato")).isEqualTo("zomato")
        assertThat(normalize("POS/Croma")).isEqualTo("croma")
    }

    @Test
    fun trailingStoreNumbersAreDropped() {
        assertThat(normalize("Reliance Fresh 1182")).isEqualTo("reliance fresh")
    }

    @Test
    fun trailingLegalSuffixesAreDropped() {
        assertThat(normalize("Zomato Ltd")).isEqualTo("zomato")
        assertThat(normalize("Flipkart Internet Pvt Ltd")).isEqualTo("flipkart internet")
    }

    /**
     * The conservative half. "Ltd" as a leading word is part of the name, and
     * stripping it everywhere would turn "Ltd Groceries" into "groceries" —
     * which then collides with a completely different shop.
     */
    @Test
    fun leadingLegalWordsArePartOfTheName() {
        assertThat(normalize("Ltd Groceries")).isEqualTo("ltd groceries")
    }

    @Test
    fun similarButDistinctMerchantsDoNotCollapse() {
        assertThat(normalize("Apollo Pharmacy")).isNotEqualTo(normalize("Apollo Hospitals"))
        assertThat(normalize("HDFC Bank")).isNotEqualTo(normalize("HDFC Ergo"))
    }

    /**
     * A merchant genuinely called "Ltd" must not normalise to the empty string:
     * the column is `UNIQUE`, so every such case would collide with every other.
     */
    @Test
    fun aNameMadeEntirelyOfSuffixesKeepsItsTokens() {
        assertThat(normalize("Ltd")).isEqualTo("ltd")
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = normalize("SWIGGY*ORDER4821")
        assertThat(normalize(once)).isEqualTo(once)
    }
}
