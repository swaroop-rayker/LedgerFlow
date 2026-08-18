package com.ledgerflow.core.common.time

import java.time.Instant
import java.time.ZoneId

/**
 * `local_date`: days since epoch in the capture device's timezone (SPEC.md §6).
 *
 * Every ledger table carries this alongside a UTC millisecond timestamp so that
 * date-bucketed queries -- "what did I spend on Tuesday", every rollup, every
 * calendar cell -- are integer comparisons rather than timezone arithmetic in
 * SQL, which SQLite does badly and which would make the answer depend on the
 * connection's locale.
 *
 * It is **derived, never entered**. Storing a day number a caller supplied
 * alongside the instant it is meant to describe gives two fields that can
 * disagree, and the disagreement surfaces as an entry that vanishes from the
 * day the user filed it under.
 */
public object LocalDates {

    /**
     * @param zone injectable so tests are not at the mercy of the machine's
     *   timezone. Production always passes the device default, which is what
     *   "device tz at capture" means.
     */
    public fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay().toInt()
}
