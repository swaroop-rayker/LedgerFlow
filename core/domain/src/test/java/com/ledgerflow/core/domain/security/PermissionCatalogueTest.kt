package com.ledgerflow.core.domain.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which row each requested permission lands on (Data permissions, V2-PLAN §3.5). */
class PermissionCatalogueTest {

    private val pkg = "com.ledgerflow.debug"

    /** `smsFullDebug`'s merged set, as `EXPECTED_MERGED_PERMISSIONS` pins it. */
    private val smsFull = listOf(
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.USE_BIOMETRIC",
        "android.permission.RECEIVE_SMS",
        "android.permission.WAKE_LOCK",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    private fun rows(
        requested: List<String> = smsFull,
        granted: Set<String> = emptySet(),
        notificationsOn: Boolean = false,
        listenerOn: Boolean = false,
    ) = PermissionCatalogue.rows(requested, pkg, { it in granted }, notificationsOn, listenerOn)

    @Test
    fun smsFull_oneRowPerKind_revocableFirst_andTheAppsOwnPermissionHasNoRow() {
        assertThat(rows().map { it.kind }).containsExactly(
            PermissionKind.NOTIFICATION_ACCESS,
            PermissionKind.RECEIVE_SMS,
            PermissionKind.CAMERA,
            PermissionKind.POST_NOTIFICATIONS,
            PermissionKind.INTERNET,
            PermissionKind.BIOMETRIC,
            PermissionKind.BACKGROUND_WORK,
        ).inOrder()
    }

    @Test
    fun playSafe_hasNoSmsRow_butStillHasNotificationAccess() {
        val kinds = rows(requested = smsFull - "android.permission.RECEIVE_SMS").map { it.kind }

        assertThat(kinds).doesNotContain(PermissionKind.RECEIVE_SMS)
        assertThat(kinds).contains(PermissionKind.NOTIFICATION_ACCESS)
    }

    @Test
    fun grants_comeFromWhatAndroidReports() {
        val held = rows(
            granted = setOf("android.permission.CAMERA"),
            notificationsOn = true,
            listenerOn = true,
        ).associate { it.kind to it.grant }

        assertThat(held[PermissionKind.CAMERA]).isEqualTo(PermissionGrant.ALLOWED)
        assertThat(held[PermissionKind.RECEIVE_SMS]).isEqualTo(PermissionGrant.NOT_ALLOWED)
        assertThat(held[PermissionKind.POST_NOTIFICATIONS]).isEqualTo(PermissionGrant.ALLOWED)
        assertThat(held[PermissionKind.NOTIFICATION_ACCESS]).isEqualTo(PermissionGrant.ALLOWED)
        assertThat(held[PermissionKind.INTERNET]).isEqualTo(PermissionGrant.AT_INSTALL)
    }

    /** WorkManager's four are one row: they are one job, and none can be switched off. */
    @Test
    fun backgroundWork_isOneRow_namingAllFour() {
        val row = rows().single { it.kind == PermissionKind.BACKGROUND_WORK }

        assertThat(row.androidNames).hasSize(4)
        assertThat(row.grant).isEqualTo(PermissionGrant.AT_INSTALL)
    }

    /** A permission the catalogue does not know is shown under its own name, never dropped. */
    @Test
    fun anUnknownPermission_isShown_notHidden() {
        val row = rows(requested = smsFull + "android.permission.READ_CONTACTS")
            .single { it.kind == PermissionKind.UNEXPLAINED }

        assertThat(row.androidNames).containsExactly("android.permission.READ_CONTACTS")
    }

    @Test
    fun onlyThePackagesOwnPrefixIsOwn() {
        assertThat(PermissionCatalogue.isOwn("$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", pkg)).isTrue()
        assertThat(PermissionCatalogue.isOwn("com.ledgerflow.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", pkg))
            .isFalse()
    }
}
