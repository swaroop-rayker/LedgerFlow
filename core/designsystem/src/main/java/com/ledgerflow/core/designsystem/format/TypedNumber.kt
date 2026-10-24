package com.ledgerflow.core.designsystem.format

/**
 * What a keyboard sent, reduced to ASCII digits and `.` (BUG62).
 *
 * [MoneyFormat.parse] and [QuantityFormat.parse] used to keep every character
 * `isDigit` accepts and then fold each one as `character - '0'`. `isDigit` is
 * true for every Unicode decimal digit, so an Arabic-Indic, Persian or
 * Devanagari digit -- which a keyboard in those languages sends into a decimal
 * field -- survived the filter and folded as a code point: Arabic-Indic one (U+0661) became
 * 1,585 rather than 1. Each digit is now replaced by its value
 * (`Character.digit`), whatever script it was typed in.
 *
 * And every decimal mark becomes `.`: the one on the key (`.`), the display
 * locale's, and Arabic's own (U+066B). Moving display and parsing to
 * `DisplayLocale` (SPEC §16 Q26) made the locale's separator `.` or `,` for
 * every phone, so an Arabic keyboard's mark would otherwise have been
 * discarded -- "12.50" typed with it read as 1250.
 *
 * Code points are written as numbers here and in the tests, never as literal
 * characters or escapes (see `SourceHasNoMangledEscapesTest` for why).
 */
internal object TypedNumber {

    fun normalize(text: String, localeSeparator: Char): String = buildString(text.length) {
        text.forEach { character ->
            val digit = Character.digit(character, RADIX)
            when {
                Character.isDigit(character) && digit >= 0 -> append('0' + digit)
                character == '.' || character == localeSeparator || character.code == ARABIC_DECIMAL_SEPARATOR ->
                    append('.')
            }
        }
    }

    private const val RADIX = 10

    /** U+066B ARABIC DECIMAL SEPARATOR. */
    private const val ARABIC_DECIMAL_SEPARATOR = 0x066B
}
