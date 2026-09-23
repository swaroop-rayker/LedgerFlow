package com.ledgerflow.feature.settings.work

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * When the automatic backup aims to run next: the next 03:00 on the device's
 * clock (ADR-0027, amended 2026-09-23).
 *
 * **An aim, not an appointment.** WorkManager treats this as the earliest the
 * pass may start. Doze holds it to the next maintenance window, a low battery
 * skips the night, and picking the phone up ends Doze — so the pass lands
 * somewhere between 03:00 and the morning, and nothing the user reads names
 * the hour. "Nightly" is the whole promise, and aiming here is what keeps it.
 *
 * Two DST cases, decided here rather than inherited from whichever call
 * happened to be used, and pinned by `BackupTimeTest` against a zone that has
 * both (the owner's IST has neither):
 * - **03:00 does not exist** — a spring-forward gap that covers it, as in
 *   Europe/Helsinki, where 03:00 jumps to 04:00. The aim moves forward by the
 *   gap, to 04:00 that morning, rather than skipping a night.
 * - **03:00 happens twice** — the autumn overlap. The first one. A pass that
 *   ran at the first 03:00 aims at tomorrow, never at the second 03:00 an hour
 *   later, so one night never produces two backups and rotates a good one out
 *   for nothing.
 *
 * Both are what [java.time.LocalDateTime.atZone] does with a gap and an
 * overlap; the test is what stops a rewrite resolving them differently.
 */
internal object BackupTime {

    /** The local time the pass aims at. */
    val AIM: LocalTime = LocalTime.of(3, 0)

    /**
     * The next [AIM] **strictly after** [nowMillis], in [zone], as epoch
     * millis. Strictly: a pass that starts at exactly 03:00 aims at tomorrow,
     * not at itself.
     */
    fun nextAfter(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMillis)
        val today = now.atZone(zone).toLocalDate()
        val todays = today.atTime(AIM).atZone(zone).toInstant()
        val next = if (todays.isAfter(now)) todays else today.plusDays(1).atTime(AIM).atZone(zone).toInstant()
        return next.toEpochMilli()
    }
}
