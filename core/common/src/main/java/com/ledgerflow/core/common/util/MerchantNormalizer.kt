package com.ledgerflow.core.common.util

import java.util.regex.Pattern

object MerchantNormalizer {

    private val rules = listOf(
        // Amazon Rules
        Pattern.compile("(?i)\\b(amazon pay|amazon seller|amzn|amazon services)\\b") to "Amazon",
        // Swiggy Rules
        Pattern.compile("(?i)\\b(swiggy instamart|swiggy delivery|swiggy)\\b") to "Swiggy",
        // Uber Rules
        Pattern.compile("(?i)\\b(uber bv|uber eats|uber rides|uber)\\b") to "Uber",
        // Zomato Rules
        Pattern.compile("(?i)\\b(zomato delivery|zomato pay|zomato)\\b") to "Zomato",
        // Netflix Rules
        Pattern.compile("(?i)\\b(netflix entertainment|netflix)\\b") to "Netflix",
        // Spotify Rules
        Pattern.compile("(?i)\\b(spotify premium|spotify)\\b") to "Spotify",
        // Zepto Rules
        Pattern.compile("(?i)\\b(zepto delivery|zept|zepto)\\b") to "Zepto",
        // JioMart Rules
        Pattern.compile("(?i)\\b(jiomart delivery|jiomrt|jiomart)\\b") to "JioMart"
    )

    fun normalize(rawMerchant: String): String {
        val trimmed = rawMerchant.trim()
        if (trimmed.isEmpty()) return "Unknown Merchant"

        // Layer 1: Apply pre-defined Regex / Alias Rules
        for ((pattern, canonical) in rules) {
            if (pattern.matcher(trimmed).find()) {
                return canonical
            }
        }

        // Layer 2: Clean common merchant suffixes
        var cleaned = trimmed
            .replace(Regex("(?i)\\b(ltd|pvt|corp|inc|gmbh|co|services|stores|pay)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "") // remove punctuation
            .replace(Regex("\\s+"), " ") // normalize spacing
            .trim()

        if (cleaned.isEmpty()) return trimmed

        // Capitalize words
        return cleaned.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
