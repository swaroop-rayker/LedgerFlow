package com.ledgerflow.core.designsystem.format

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/** A budget as its headroom (step F5): what Home and Budgets both say. */
class BudgetWordsTest {

    @Test
    fun headroom_isWhatIsLeft_orHowFarOver() {
        assertThat(BudgetWords.headroom(Money(4_750_00), Money(5_000_00), "INR"))
            .isEqualTo("₹250.00 left of ₹5,000.00")
        assertThat(BudgetWords.headroom(Money(5_000_00), Money(5_000_00), "INR"))
            .isEqualTo("₹0.00 left of ₹5,000.00")
        assertThat(BudgetWords.headroom(Money(5_300_00), Money(5_000_00), "INR"))
            .isEqualTo("₹300.00 over ₹5,000.00")
    }
}
