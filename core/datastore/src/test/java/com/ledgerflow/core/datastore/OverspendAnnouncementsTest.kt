package com.ledgerflow.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The overspending-warning record (V2-PLAN §3.4): one entry per budget, opaque, current period only. */
class OverspendAnnouncementsTest {

    @Test
    fun anEntry_isTheBudgetIdAndThePeriodStart_nothingElse() {
        assertThat(announcementEntry("b1", 20_727)).isEqualTo("b1@20727")
    }

    @Test
    fun aNewPeriod_replacesTheOld_andOtherBudgetsStay() {
        val before = setOf("b1@20697", "b2@20700")
        assertThat(before.withAnnouncement("b1", 20_727)).containsExactly("b1@20727", "b2@20700")
    }

    @Test
    fun anIdThatPrefixesAnother_isNotConfusedWithIt() {
        // "b1" must not remove "b10"'s record.
        assertThat(setOf("b10@20700").withAnnouncement("b1", 20_727)).containsExactly("b10@20700", "b1@20727")
    }
}
