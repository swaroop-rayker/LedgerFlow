package com.ledgerflow.core.domain.ledger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemNameNormalizerTest {

    @Test
    fun normalize_foldsCaseAndPunctuation() {
        assertThat(ItemNameNormalizer.normalize("  Toor  Dal (1kg) ")).isEqualTo("toor dal 1kg")
    }

    @Test
    fun normalize_foldsAccents() {
        assertThat(ItemNameNormalizer.normalize("Café Latté")).isEqualTo("cafe latte")
    }

    /**
     * The line this normaliser must not cross. Quantity is part of what the
     * product *is*, so collapsing it would merge two different SKUs into one
     * learned category mapping at P4.
     */
    @Test
    fun normalize_keepsQuantitiesDistinct() {
        assertThat(ItemNameNormalizer.normalize("Milk 500ml"))
            .isNotEqualTo(ItemNameNormalizer.normalize("Milk 1L"))
    }

    @Test
    fun normalize_blankInputStaysBlank() {
        assertThat(ItemNameNormalizer.normalize("   ---   ")).isEmpty()
    }
}
