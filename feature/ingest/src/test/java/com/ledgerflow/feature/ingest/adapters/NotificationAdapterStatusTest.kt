package com.ledgerflow.feature.ingest.adapters

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import org.junit.Test

/**
 * The notification source's status mapping, in **both** flavours.
 *
 * This test lives in the shared `src/test` source set on purpose: it runs under
 * `testSmsFullDebugUnitTest` *and* `testPlaySafeDebugUnitTest`, and both must
 * pass identically. Notification ingest is co-equal and unflavoured (SPEC.md
 * §3.1) — the day it starts behaving differently in the Play build, this file
 * stops compiling in one of the two and that is the intended alarm.
 */
class NotificationAdapterStatusTest {

    @Test
    fun statusFor_listenerEnabled_isReady() {
        assertThat(NotificationAdapter.statusFor(listenerEnabled = true))
            .isEqualTo(IngestSourceStatus.READY)
    }

    /**
     * Actionable, and the only actionable state this source has: §5.2's Settings
     * deep link is what fixes it, since notification access cannot be granted
     * from inside an app.
     */
    @Test
    fun statusFor_listenerDisabled_isPermissionRequired() {
        assertThat(NotificationAdapter.statusFor(listenerEnabled = false))
            .isEqualTo(IngestSourceStatus.PERMISSION_REQUIRED)
    }

    /**
     * Never [IngestSourceStatus.UNSUPPORTED_IN_BUILD], in either flavour.
     *
     * That value means "this build ships no capture path and never will", which
     * is true of `playSafe`'s SMS source and of nothing else. Reporting it here
     * would tell a Play-build user that the app's primary ingest source is
     * unavailable to them, which is the exact opposite of D-04.
     */
    @Test
    fun statusFor_neverReportsUnsupportedInBuild() {
        listOf(true, false).forEach { enabled ->
            assertThat(NotificationAdapter.statusFor(listenerEnabled = enabled))
                .isNotEqualTo(IngestSourceStatus.UNSUPPORTED_IN_BUILD)
        }
    }
}
