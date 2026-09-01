package com.ledgerflow.core.common.time

import android.os.Process
import android.os.SystemClock

/**
 * How long this process has been running, in millis.
 *
 * A separate primitive from [Clock] rather than a second method on it, because
 * the two answer different questions and are wrong in different ways. [Clock] is
 * wall clock: it survives a reboot and it can jump backwards when the user or
 * NTP moves the date. This is monotonic since process start: it cannot jump, and
 * it means nothing across a process boundary.
 *
 * The one caller is §5.2's health evaluation, which needs to know whether the
 * process has been up long enough for "the listener is not bound" to be evidence
 * of anything — see `ListenerHealth.MIN_UPTIME_BEFORE_DEAD_MILLIS`. Asking the
 * wall clock that question would make a cold start after a date change look like
 * a six-hour outage.
 *
 * A `fun interface` so a test can pass a lambda; the real implementation reads
 * two platform clocks and is therefore not something a JVM unit test can hold.
 */
public fun interface ProcessUptime {
    public fun uptimeMillis(): Long

    public companion object {

        /**
         * The platform's answer.
         *
         * `Process.getStartElapsedRealtime()` and `SystemClock.elapsedRealtime()`
         * are the same clock — elapsed real time since boot, including deep
         * sleep — so the difference is exact rather than an estimate, and both
         * halves are available at minSdk 26.
         */
        public val System: ProcessUptime = ProcessUptime {
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        }
    }
}
