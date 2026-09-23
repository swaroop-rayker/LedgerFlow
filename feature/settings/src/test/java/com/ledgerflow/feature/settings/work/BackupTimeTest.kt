package com.ledgerflow.feature.settings.work

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

/**
 * Where the automatic backup aims its next pass (ADR-0027, amended 2026-09-23;
 * §8 BUG31).
 *
 * The owner's phone is in IST, which has no DST, so the gap and overlap cases
 * are written against **Europe/Helsinki**: its spring-forward jumps from 03:00
 * straight to 04:00 and its autumn fall-back repeats 03:00–03:59, which puts
 * both awkward cases on exactly the hour this aims at. A zone that changes at
 * 02:00 would pass these by accident.
 */
class BackupTimeTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val helsinki = ZoneId.of("Europe/Helsinki")

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        at(ZoneOffset.UTC, year, month, day, hour, minute)

    @Test
    fun anEvening_aimsAtThreeTomorrowMorning() {
        val next = BackupTime.nextAfter(at(ist, 2026, 9, 23, 21, 50), ist)

        assertThat(next).isEqualTo(at(ist, 2026, 9, 24, 3))
    }

    @Test
    fun beforeThree_aimsAtThisMorning() {
        val next = BackupTime.nextAfter(at(ist, 2026, 9, 24, 1, 15), ist)

        assertThat(next).isEqualTo(at(ist, 2026, 9, 24, 3))
    }

    @Test
    fun exactlyThree_aimsAtTomorrow_notAtItself() {
        val next = BackupTime.nextAfter(at(ist, 2026, 9, 24, 3), ist)

        assertThat(next).isEqualTo(at(ist, 2026, 9, 25, 3))
    }

    /** BUG31 itself: a pass Doze held until morning must not become the new anchor. */
    @Test
    fun Bug31_aLateRun_aimsAtThree_notADayAfterItself() {
        val ranAt = at(ist, 2026, 9, 24, 6, 40)

        val next = BackupTime.nextAfter(ranAt, ist)

        assertThat(next).isEqualTo(at(ist, 2026, 9, 25, 3))
        assertThat(next).isNotEqualTo(ranAt + DAY_MILLIS)
    }

    @Test
    fun theDayBoundary_crossesMonthAndYear() {
        val next = BackupTime.nextAfter(at(ist, 2026, 12, 31, 23), ist)

        assertThat(next).isEqualTo(at(ist, 2027, 1, 1, 3))
    }

    /** The device's zone decides, not UTC: one instant, two different mornings. */
    @Test
    fun theDevicesZone_decidesWhichMorning() {
        // 22:00 UTC on the 23rd is 03:30 IST on the 24th — past this morning's aim.
        val instant = utc(2026, 9, 23, 22)

        assertThat(BackupTime.nextAfter(instant, ist)).isEqualTo(at(ist, 2026, 9, 25, 3))
        assertThat(BackupTime.nextAfter(instant, ZoneOffset.UTC)).isEqualTo(utc(2026, 9, 24, 3))
    }

    /**
     * 2027-03-28 in Helsinki: at 01:00 UTC, 03:00 EET becomes 04:00 EEST, so
     * 03:00 does not exist. The aim moves forward by the gap — 04:00 local,
     * 01:00 UTC — and the night is not skipped.
     */
    @Test
    fun aSpringForwardGapOverThree_aimsAtFourThatMorning_notTomorrow() {
        val fourEest = ZonedDateTime.of(2027, 3, 28, 4, 0, 0, 0, helsinki).toInstant().toEpochMilli()
        assertThat(fourEest).isEqualTo(utc(2027, 3, 28, 1))

        assertThat(BackupTime.nextAfter(at(helsinki, 2027, 3, 27, 22), helsinki)).isEqualTo(fourEest)
        assertThat(BackupTime.nextAfter(utc(2027, 3, 28, 0, 30), helsinki)).isEqualTo(fourEest)
    }

    @Test
    fun theNightAfterTheGap_isBackAtThree() {
        val next = BackupTime.nextAfter(utc(2027, 3, 28, 1, 5), helsinki)

        // 03:00 EEST on the 29th is 00:00 UTC.
        assertThat(next).isEqualTo(utc(2027, 3, 29, 0))
    }

    /**
     * 2026-10-25 in Helsinki: at 01:00 UTC, 04:00 EEST falls back to 03:00 EET,
     * so 03:00 happens at 00:00 UTC and again at 01:00 UTC. The aim is the
     * first, and a pass after either one aims at tomorrow — never at the second
     * 03:00 an hour later, which would write two backups in one night.
     */
    @Test
    fun anAutumnOverlap_aimsAtTheFirstThree_andRunsOnce() {
        val firstThree = utc(2026, 10, 25, 0)
        val tomorrow = utc(2026, 10, 26, 1) // 03:00 EET

        assertThat(BackupTime.nextAfter(utc(2026, 10, 24, 23, 30), helsinki)).isEqualTo(firstThree)
        assertThat(BackupTime.nextAfter(firstThree + MINUTE_MILLIS, helsinki)).isEqualTo(tomorrow)
        assertThat(BackupTime.nextAfter(utc(2026, 10, 25, 1, 30), helsinki)).isEqualTo(tomorrow)
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
    }
}
