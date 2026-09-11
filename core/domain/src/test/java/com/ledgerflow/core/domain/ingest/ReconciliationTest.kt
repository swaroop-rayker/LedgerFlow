package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * §5.3's reconciliation: `|Σitems + Σtax − Σdiscount − total| ≤ max(₹1, 0.5%)`.
 *
 * The arithmetic is integers throughout (Law 3), and the interesting cases are
 * the three boundaries: the floor, the proportional term, and the difference
 * between "does not balance" and "cannot be checked".
 */
class ReconciliationTest {

    private fun item(minor: Long) = ExtractedLineItem(name = "item", total = Money(minor))

    private fun tax(minor: Long) =
        ExtractedLineItem(name = "CGST", kind = LineItemKind.TAX, total = Money(minor))

    private fun discount(minor: Long) =
        ExtractedLineItem(name = "DISCOUNT", kind = LineItemKind.DISCOUNT, total = Money(minor))

    @Test
    fun aBillWhosePartsSumExactly_balances() {
        val verdict = Reconciliation.of(
            listOf(item(70_900L), tax(3_546L), discount(-5_000L)),
            Money(69_446L),
        )

        assertThat(verdict).isEqualTo(Reconciliation.Balanced(Money.ZERO))
    }

    /**
     * A discount is negative, so §5.3's `− Σ(discount)` is a plain addition.
     *
     * The failure this pins is the sign convention getting reversed somewhere:
     * a discount that *increased* the bill would still be arithmetic, and the
     * delta would be twice the discount rather than zero.
     */
    @Test
    fun aDiscount_reducesTheParts() {
        val withDiscount = Reconciliation.of(listOf(item(10_000L), discount(-2_000L)), Money(8_000L))
        val withoutIt = Reconciliation.of(listOf(item(10_000L)), Money(8_000L))

        assertThat(withDiscount).isInstanceOf(Reconciliation.Balanced::class.java)
        assertThat(withoutIt).isInstanceOf(Reconciliation.Unbalanced::class.java)
    }

    /** The floor: ₹1 on a bill too small for 0.5% to reach a rupee. */
    @Test
    fun aRupeeOff_onASmallBill_isWithinTheFloor() {
        assertThat(Reconciliation.of(listOf(item(10_100L)), Money(10_000L)))
            .isInstanceOf(Reconciliation.Balanced::class.java)
        assertThat(Reconciliation.of(listOf(item(10_101L)), Money(10_000L)))
            .isInstanceOf(Reconciliation.Unbalanced::class.java)
    }

    /**
     * The proportional term, and the order the integers are applied in.
     *
     * 0.5% of ₹10,000 is ₹50 = 5000 paise. Dividing before multiplying —
     * `1_000_000 / 1000 * 5` — happens to give the same answer here, so the
     * case that separates them is one where the total is not a round multiple
     * of 1000 minor units.
     */
    @Test
    fun theProportionalTerm_isHalfAPercent() {
        assertThat(Reconciliation.toleranceFor(Money(1_000_000L))).isEqualTo(Money(5_000L))
        // 47300 * 5 / 1000 = 236. Dividing first gives 47 * 5 = 235, which is
        // a tighter tolerance than the spec's, arrived at by accident.
        assertThat(Reconciliation.toleranceFor(Money(47_300L))).isEqualTo(Money(236L))
    }

    @Test
    fun theToleranceIsTheLargerOfTheTwoTerms() {
        // Small bill: the ₹1 floor wins over 0.5% of ₹100.
        assertThat(Reconciliation.toleranceFor(Money(10_000L))).isEqualTo(Money(100L))
        // Large bill: 0.5% wins over ₹1.
        assertThat(Reconciliation.toleranceFor(Money(1_000_000L))).isEqualTo(Money(5_000L))
    }

    @Test
    fun theDeltaIsSigned_soTheBannerCanSayWhichWay() {
        val over = Reconciliation.of(listOf(item(20_000L)), Money(10_000L))
        val under = Reconciliation.of(listOf(item(5_000L)), Money(10_000L))

        assertThat(over.delta).isEqualTo(Money(10_000L))
        assertThat(under.delta).isEqualTo(Money(-5_000L))
    }

    /**
     * **Not checked is not the same as failed.**
     *
     * Reporting `Unbalanced` against a null total would compute the delta
     * against zero, so the banner would state the sum of the items as a
     * discrepancy — a specific, wrong, entirely believable number.
     */
    @Test
    fun noTotal_cannotBeReconciled_ratherThanFailing() {
        val verdict = Reconciliation.of(listOf(item(20_000L)), null)

        assertThat(verdict).isEqualTo(Reconciliation.NotPossible)
        assertThat(verdict.delta).isEqualTo(Money.ZERO)
    }

    /**
     * A `SUBTOTAL` or `TOTAL` among the lines is the sum, not a part of it.
     *
     * If one were counted, a perfectly ordinary bill would come out at roughly
     * double — and the check would report a delta the size of the bill, which
     * reads as an extraction failure rather than as a reconciliation bug.
     */
    @Test
    fun aSumAmongTheLines_isNotCountedAsAPart() {
        val lines = listOf(
            item(10_000L),
            ExtractedLineItem(
                name = "SUB TOTAL",
                kind = LineItemKind.UNALLOCATED,
                total = Money(10_000L),
            ),
        )

        assertThat(Reconciliation.of(lines, Money(10_000L)))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    /** A line the extractor read with no amount contributes nothing, not zero-crash. */
    @Test
    fun aLineWithNoAmount_isSkipped() {
        val lines = listOf(item(10_000L), ExtractedLineItem(name = "unreadable"))

        assertThat(Reconciliation.of(lines, Money(10_000L)))
            .isInstanceOf(Reconciliation.Balanced::class.java)
    }

    @Test
    fun noLinesAtAll_isUnbalancedByTheWholeTotal() {
        val verdict = Reconciliation.of(emptyList(), Money(10_000L))

        assertThat(verdict).isInstanceOf(Reconciliation.Unbalanced::class.java)
        assertThat(verdict.delta).isEqualTo(Money(-10_000L))
    }
}
