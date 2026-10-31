package com.ledgerflow.security

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.security.PermissionCatalogue
import com.ledgerflow.core.domain.security.PermissionKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Data permissions explains every permission the app ships with (V2-PLAN §3.5).
 *
 * Reads the packaged manifest -- the merged one, with every library's
 * entries -- for whichever flavour this run builds, and fails on any
 * permission the page would show as "unexplained". Adding a dependency that
 * merges a permission therefore means writing the sentence that says why,
 * the same day `EXPECTED_MERGED_PERMISSIONS` is updated (ADR-0021).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DataPermissionsCoverTheManifestTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION") // The flags overload is API 33+; this reads the same field.
    private val requested: List<String> = app.packageManager
        .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        ?.toList()
        .orEmpty()

    /** The merged manifest, not only the app's own: INTERNET comes from ML Kit, WAKE_LOCK from WorkManager. */
    @Test
    fun theManifestReadIsTheMergedOne() {
        assertThat(requested).containsAtLeast(
            "android.permission.CAMERA",
            "android.permission.INTERNET",
            "android.permission.WAKE_LOCK",
        )
    }

    @Test
    fun everyRequestedPermission_hasARowThatExplainsIt() {
        requested.filterNot { PermissionCatalogue.isOwn(it, app.packageName) }.forEach { name ->
            assertWithMessage("$name has no row on Data permissions; add it to PermissionCatalogue")
                .that(PermissionCatalogue.kindOf(name))
                .isNotEqualTo(PermissionKind.UNEXPLAINED)
        }
    }

    /** D-04: the Play build holds no SMS permission, so its page has no SMS row. */
    @Test
    fun theSmsRow_isThereExactlyWhenTheFlavourReceivesSms() {
        val kinds = PermissionCatalogue.rows(
            requested = requested,
            packageName = app.packageName,
            granted = { false },
            notificationsOn = false,
            listenerOn = false,
        ).map { it.kind }

        val playSafe = ".playsafe" in app.packageName
        assertThat(PermissionKind.RECEIVE_SMS in kinds).isEqualTo(!playSafe)
        assertThat(kinds).contains(PermissionKind.NOTIFICATION_ACCESS)
    }
}
