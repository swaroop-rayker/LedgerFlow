package com.ledgerflow.core.designsystem.format

import com.ledgerflow.core.model.CurrencyDisplay
import com.ledgerflow.core.model.LedgerType

/**
 * What an amount looks like while it is hidden (Hide amounts on Home, V2-PLAN
 * §3.5, SPEC §9.6).
 *
 * **The same four dots for every amount.** A mask as long as the figure would
 * say how big it is, which is most of what someone glancing at the screen
 * wants to know. The currency symbol stays, so a hidden amount still reads as
 * money, and a ledger row keeps its sign, which its section heading already
 * says.
 *
 * Text, not a drawing, so a hidden amount can sit inside a sentence ("₹••••
 * expected by 31 Oct") as well as stand alone in [LfMaskedAmount][com.ledgerflow.core.designsystem.component.LfMaskedAmount].
 */
public object AmountMask {

    /** Four bullets (U+2022), as a code point (CLAUDE.md §5). */
    public val DOTS: String = Char(BULLET).toString().repeat(DOT_COUNT)

    /** What TalkBack says in place of an amount. Never the figure, never its length. */
    public const val SPOKEN: String = "amount hidden"

    /** The action a hidden amount offers a screen reader; one tap shows every amount on the screen. */
    public const val REVEAL_LABEL: String = "Show amounts"

    /** `₹••••`: [MoneyFormat.symbolised]'s shape, hidden. */
    public fun symbolised(currencyCode: String): String = bidiIsolated(CurrencyDisplay.symbolOf(currencyCode)) + DOTS

    /** `-₹••••` / `+₹••••`: [MoneyFormat.directional]'s shape, hidden. */
    public fun directional(currencyCode: String, ledger: LedgerType): String {
        val sign = when (ledger) {
            LedgerType.DEBIT -> '-'
            LedgerType.CREDIT -> '+'
        }
        return sign + symbolised(currencyCode)
    }

    /** `spent, amount hidden`: [MoneyFormat.spokenDirectional]'s shape, hidden. */
    public fun spokenDirectional(ledger: LedgerType): String {
        val verb = when (ledger) {
            LedgerType.DEBIT -> "spent"
            LedgerType.CREDIT -> "received"
        }
        return "$verb, $SPOKEN"
    }

    private const val BULLET = 0x2022
    private const val DOT_COUNT = 4
}
