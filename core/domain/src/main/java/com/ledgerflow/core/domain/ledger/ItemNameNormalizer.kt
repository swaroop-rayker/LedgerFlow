package com.ledgerflow.core.domain.ledger

import java.text.Normalizer
import java.util.Locale

/**
 * Produces `line_item.normalized_name` (SPEC.md §6.1).
 *
 * Deliberately much simpler than `MerchantNormalizer`. That column is `UNIQUE`
 * and decides merchant identity, so it has to be conservative and stable
 * forever. This one is an index for text search (§5.6) and, at P4, the lookup
 * key for `item_category_memory` -- a wrong collapse costs a mis-suggested
 * category the user overrides, not a merchant's history split in two.
 *
 * So it does the three things every consumer wants and nothing clever: fold
 * accents, case-fold, and reduce punctuation to single spaces. No suffix
 * stripping, no token dropping -- "Milk 500ml" and "Milk 1L" are different
 * products and must not normalise together.
 */
public object ItemNameNormalizer {

    public fun normalize(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .trim()

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
}
