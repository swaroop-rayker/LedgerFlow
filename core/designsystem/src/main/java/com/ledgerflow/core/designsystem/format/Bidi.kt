package com.ledgerflow.core.designsystem.format

/**
 * [text] wrapped in a Unicode first-strong isolate (FSI … PDI) when it holds
 * right-to-left letters; unchanged otherwise (BUG61).
 *
 * Every style reads a paragraph in its text's own direction, decided by the
 * first strong letter. A right-to-left *symbol* at the front of a longer string
 * -- the dirham's "د.إ" -- would otherwise decide it, and an AED amount or the
 * "د.إ  UAE Dirham" row would be laid out right-to-left on an English screen:
 * sign moved to the end, symbol and name swapped. Isolated, the symbol still
 * draws as one right-to-left unit and is skipped when the paragraph's direction
 * is chosen.
 *
 * Only for text built for the screen. The isolates are invisible but real
 * characters, so nothing that is written to a file or compared as data goes
 * through here.
 */
public fun bidiIsolated(text: String): String =
    if (text.any(::isStrongRightToLeft)) "$FIRST_STRONG_ISOLATE$text$POP_DIRECTIONAL_ISOLATE" else text

private fun isStrongRightToLeft(char: Char): Boolean = when (Character.getDirectionality(char)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    -> true
    else -> false
}

private const val FIRST_STRONG_ISOLATE = '\u2068'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'
