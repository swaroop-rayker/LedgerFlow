package com.ledgerflow.feature.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The words step F5 gives Home: the status line's backup half. A budget's
 * headroom ("250 left of 5,000") is `BudgetWords`, tested in `:core:designsystem`.
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
}
