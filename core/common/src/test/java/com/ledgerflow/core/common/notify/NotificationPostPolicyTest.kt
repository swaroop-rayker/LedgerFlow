package com.ledgerflow.core.common.notify

import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [NotificationPostPolicy.mayPost], which is the half of the rule that was
 * wrong in one of its two former copies.
 *
 * A pure test rather than a Robolectric one on purpose. The defect was that
 * `checkSelfPermission(POST_NOTIFICATIONS)` answers `DENIED` below API 33
 * because the platform has no definition for the permission there — and
 * Robolectric does not model that. It grants or denies exactly what a test asks
 * it to, at any configured `sdk`, so a shadowed test would have passed both
 * before and after the fix. Feeding the three inputs in directly is the only
 * way this assertion means anything off a 26/27 device.
 */
class NotificationPostPolicyTest {

    private companion object {
        const val OLDEST = Build.VERSION_CODES.O // 26 == minSdk
        const val BEFORE_RUNTIME_GRANT = Build.VERSION_CODES.S_V2 // 32
        const val TIRAMISU = Build.VERSION_CODES.TIRAMISU // 33
        const val NEWEST = 36
    }

    /**
     * **The bug, as a test.** Below Tiramisu the permission does not exist, so
     * the platform's answer is `DENIED` and it must not be believed. Delete the
     * `sdkInt < TIRAMISU` clause and this is the assertion that goes red.
     */
    @Test
    fun mayPost_belowTiramisuWithPermissionDenied_isTrue() {
        assertThat(
            NotificationPostPolicy.mayPost(
                sdkInt = OLDEST,
                postNotificationsGranted = { false },
                notificationsEnabled = { true },
            ),
        ).isTrue()

        assertThat(
            NotificationPostPolicy.mayPost(
                sdkInt = BEFORE_RUNTIME_GRANT,
                postNotificationsGranted = { false },
                notificationsEnabled = { true },
            ),
        ).isTrue()
    }

    /**
     * Below Tiramisu the permission is not merely disbelieved, it is never
     * asked. Reading it would be a binder call for an answer that cannot be
     * used, on a path that runs once per captured message.
     */
    @Test
    fun mayPost_belowTiramisu_neverQueriesThePermission() {
        var asked = false

        NotificationPostPolicy.mayPost(
            sdkInt = BEFORE_RUNTIME_GRANT,
            postNotificationsGranted = { asked = true; false },
            notificationsEnabled = { true },
        )

        assertThat(asked).isFalse()
    }

    /**
     * At Tiramisu and above the grant is real and a refusal is final. This is
     * the assertion that would go red if the API-level guard were widened into
     * an unconditional `true` — the lazy way to "fix" the case above, and the
     * one that would post nothing while claiming everything is fine.
     */
    @Test
    fun mayPost_atTiramisuWithPermissionDenied_isFalse() {
        assertThat(
            NotificationPostPolicy.mayPost(
                sdkInt = TIRAMISU,
                postNotificationsGranted = { false },
                notificationsEnabled = { true },
            ),
        ).isFalse()

        assertThat(
            NotificationPostPolicy.mayPost(
                sdkInt = NEWEST,
                postNotificationsGranted = { false },
                notificationsEnabled = { true },
            ),
        ).isFalse()
    }

    @Test
    fun mayPost_atTiramisuWithPermissionGranted_isTrue() {
        assertThat(
            NotificationPostPolicy.mayPost(
                sdkInt = TIRAMISU,
                postNotificationsGranted = { true },
                notificationsEnabled = { true },
            ),
        ).isTrue()
    }

    /**
     * The other half, and it is not a permission: the app or its channel muted
     * in system settings. It applies at **every** API level, including the ones
     * the permission guard waves through, which is exactly where an
     * `sdkInt < TIRAMISU` short-circuit written as an early `return true` would
     * have lost it.
     */
    @Test
    fun mayPost_notificationsDisabled_isFalseAtEveryApiLevel() {
        listOf(OLDEST, BEFORE_RUNTIME_GRANT, TIRAMISU, NEWEST).forEach { sdk ->
            assertThat(
                NotificationPostPolicy.mayPost(
                    sdkInt = sdk,
                    postNotificationsGranted = { true },
                    notificationsEnabled = { false },
                ),
            ).isFalse()
        }
    }
}
