package com.ledgerflow.feature.ingest.adapters

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import org.junit.Test

/**
 * `smsFull`'s SMS source: a real capture path whose status depends on the
 * device and the grant (SPEC.md §3.1).
 *
 * The `playSafe` half of this pair is `SmsAdapterPlaySafeTest` in
 * `src/testPlaySafe`, asserting the opposite. Between them they are the D-04
 * split as a test: same class name, same source type, incompatible answers, and
 * neither flavour can compile the other's test.
 */
class SmsAdapterSmsFullTest {

    @Test
    fun statusFor_telephonyAndPermission_isReady() {
        assertThat(SmsAdapter.statusFor(hasTelephony = true, permissionGranted = true))
            .isEqualTo(IngestSourceStatus.READY)
    }

    @Test
    fun statusFor_permissionNotGranted_isPermissionRequired() {
        assertThat(SmsAdapter.statusFor(hasTelephony = true, permissionGranted = false))
            .isEqualTo(IngestSourceStatus.PERMISSION_REQUIRED)
    }

    /**
     * Hardware outranks the grant, both ways round.
     *
     * On a Wi-Fi tablet `RECEIVE_SMS` can be granted and still never deliver a
     * message. Reporting `PERMISSION_REQUIRED` there would send the user to a
     * prompt that changes nothing, so the device answer wins even when the
     * permission is held.
     */
    @Test
    fun statusFor_withoutTelephony_isUnavailableRegardlessOfPermission() {
        assertThat(SmsAdapter.statusFor(hasTelephony = false, permissionGranted = true))
            .isEqualTo(IngestSourceStatus.UNAVAILABLE_ON_DEVICE)
        assertThat(SmsAdapter.statusFor(hasTelephony = false, permissionGranted = false))
            .isEqualTo(IngestSourceStatus.UNAVAILABLE_ON_DEVICE)
    }

    /**
     * Never [IngestSourceStatus.UNSUPPORTED_IN_BUILD] — this build *is* the one
     * that supports it. Saying otherwise would leave a sideloaded user with a
     * working receiver and a screen telling them the feature does not exist.
     */
    @Test
    fun statusFor_neverReportsUnsupportedInBuild() {
        listOf(true, false).forEach { telephony ->
            listOf(true, false).forEach { granted ->
                assertThat(SmsAdapter.statusFor(telephony, granted))
                    .isNotEqualTo(IngestSourceStatus.UNSUPPORTED_IN_BUILD)
            }
        }
    }
}
