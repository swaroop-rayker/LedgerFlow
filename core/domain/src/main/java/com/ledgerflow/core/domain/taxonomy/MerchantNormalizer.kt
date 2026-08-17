package com.ledgerflow.core.domain.taxonomy

import java.text.Normalizer
import java.util.Locale

/**
 * Produces `merchant.normalized_key` (SPEC.md §5.5).
 *
 * The column is `UNIQUE`, so this function decides what counts as "the same
 * merchant". It has to be **stable forever**: change it and existing rows keep
 * their old keys while new ones get different keys for the same shop, which
 * silently splits a merchant's history in two.
 *
 * It is deliberately conservative. Aggressive normalisation collapses genuinely
 * distinct merchants ("Apollo Pharmacy" and "Apollo Hospitals"), and a wrong
 * merge is far harder for a user to undo than a duplicate is to merge.
 *
 * Fuzzy matching (Jaro-Winkler ≥ 0.88 per §5.5) is a *separate* concern and
 * lands with the ingest pipeline at P2: this is exact-key identity, that is
 * "did this SMS mean an existing merchant?".
 */
public object MerchantNormalizer {

    /**
     * Legal-form suffixes, stripped only when they are the final token. Trailing
     * so that "Ltd Groceries" -- a real shop name -- is untouched.
     */
    private val LEGAL_SUFFIXES = setOf(
        "ltd", "limited", "pvt", "private", "llp", "inc", "incorporated",
        "co", "company", "corp", "corporation", "plc", "gmbh", "bv", "sa",
    )

    /**
     * Payment-rail noise that appears glued to merchant names in UPI and card
     * descriptors: "SWIGGY*ORDER", "AMAZON IN*8QW12".
     */
    private val RAIL_PREFIXES = listOf("upi/", "pos/", "neft/", "imps/", "ach/")

    public fun normalize(raw: String): String {
        val ascii = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)

        val derailed = RAIL_PREFIXES.fold(ascii) { value, prefix -> value.removePrefix(prefix) }

        // Everything after a '*' in a card/UPI descriptor is a reference code,
        // not part of the name.
        val withoutReference = derailed.substringBefore('*')

        val tokens = withoutReference
            .replace(NON_ALPHANUMERIC, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            // A trailing all-digit token is a store or terminal number.
            .dropLastWhile { it.all(Char::isDigit) }

        val withoutSuffix = tokens.dropLastWhile { it in LEGAL_SUFFIXES }

        // Falling back to the tokens rather than to "" matters: a merchant
        // genuinely called "Ltd" would otherwise normalise to the empty string
        // and collide with every other such case on a UNIQUE column.
        return (withoutSuffix.ifEmpty { tokens }).joinToString(" ")
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
}
