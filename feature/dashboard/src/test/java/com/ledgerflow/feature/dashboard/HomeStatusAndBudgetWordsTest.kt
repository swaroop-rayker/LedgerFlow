package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The words step F5 gives Home: the status line's backup half, and a budget
 * as what is left of it (the canvas's "250 left of 5,000").
 */
class HomeStatusAndBudgetWordsTest {

    @Test
    fun backupPhrase_saysWhenInWholeDays() {
        assertThat(backupPhrase(null)).isNull() // not read yet: say nothing
        assertThat(backupPhrase(LastBackup.Never)).isEqualTo("No backup yet")
        assertThat(backupPhrase(LastBackup.DaysAgo(0))).isEqualTo("Backed up today")
        assertThat(backupPhrase(LastBackup.DaysAgo(1))).isEqualTo("Backed up yesterday")
        assertThat(backupPhrase(LastBackup.DaysAgo(4))).isEqualTo("Backed up 4 days ago")
    }

    @Test
    fun budgetLeft_isTheHeadroom_orHowFarOver() {
        assertThat(budgetLeft(spentMinor = 4_750_00, budgetMinor = 5_000_00, currency = "INR"))
            .isEqualTo("₹250.00 left of ₹5,000.00")
        assertThat(budgetLeft(spentMinor = 5_000_00, budgetMinor = 5_000_00, currency = "INR"))
            .isEqualTo("₹0.00 left of ₹5,000.00")
        assertThat(budgetLeft(spentMinor = 5_300_00, budgetMinor = 5_000_00, currency = "INR"))
            .isEqualTo("₹300.00 over ₹5,000.00")
    }
}
