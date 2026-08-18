package com.ledgerflow.core.model

/**
 * Presentation facts about a currency: its symbol, its name, and what a screen
 * reader should call it.
 *
 * Hardcoded rather than read from `java.util.Currency`, for the reason §5.8
 * already gives about exponents: the platform's currency tables have been wrong
 * on some OEM ROMs, and `getSymbol()` additionally falls back to the ISO code
 * depending on the device locale — so "₹" would silently become "INR" on a
 * phone set to a locale that does not know the symbol. A finance app's own
 * currency should not depend on which keyboard the user installed.
 *
 * Deliberately short and INR-first (§3.1). This is the list onboarding offers,
 * and a currency outside it degrades to its ISO code rather than to nothing.
 *
 * Plain `String`s so `:core:model` stays free of Android and of Compose
 * (CLAUDE.md §3).
 */
public object CurrencyDisplay {

    private data class Entry(val symbol: String, val name: String, val spokenUnit: String)

    private val ENTRIES: Map<String, Entry> = linkedMapOf(
        "INR" to Entry("₹", "Indian Rupee", "rupees"),
        "USD" to Entry("$", "US Dollar", "dollars"),
        "EUR" to Entry("€", "Euro", "euros"),
        "GBP" to Entry("£", "British Pound", "pounds"),
        "AED" to Entry("د.إ", "UAE Dirham", "dirhams"),
        "SGD" to Entry("S$", "Singapore Dollar", "Singapore dollars"),
        "AUD" to Entry("A$", "Australian Dollar", "Australian dollars"),
        "JPY" to Entry("¥", "Japanese Yen", "yen"),
    )

    /** In display order. `linkedMapOf` above is what makes that order stable. */
    public val supportedCodes: List<String> = ENTRIES.keys.toList()

    public fun symbolOf(code: String): String =
        ENTRIES[code.uppercase()]?.symbol ?: code.uppercase()

    public fun nameOf(code: String): String =
        ENTRIES[code.uppercase()]?.name ?: code.uppercase()

    /**
     * What TalkBack should say, per §9.6: "spent 1,240 **rupees** on groceries",
     * not "spent 1,240 ₹". A symbol is a glyph, and screen readers announce
     * glyphs inconsistently or not at all.
     */
    public fun spokenUnitOf(code: String): String =
        ENTRIES[code.uppercase()]?.spokenUnit ?: code.uppercase()
}
