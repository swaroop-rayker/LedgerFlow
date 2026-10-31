package com.ledgerflow.feature.settings.permissions

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.security.AppPermissions
import com.ledgerflow.core.domain.security.HeldPermission
import com.ledgerflow.core.domain.security.PermissionGrant
import com.ledgerflow.core.domain.security.PermissionKind
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Security & privacy > Data permissions (V2-PLAN §3.5): one row per
 * permission held, its state and its reason; the ones a user can take back
 * open Android's page for them, the rest are not tappable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class DataPermissionsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun held(kind: PermissionKind, grant: PermissionGrant, vararg names: String) =
        HeldPermission(kind, grant, names.toList())

    /** `smsFull` with capture on, the camera refused and notifications allowed. */
    private val smsFull = permissionRows(
        listOf(
            held(PermissionKind.NOTIFICATION_ACCESS, PermissionGrant.ALLOWED),
            held(PermissionKind.RECEIVE_SMS, PermissionGrant.ALLOWED, "android.permission.RECEIVE_SMS"),
            held(PermissionKind.CAMERA, PermissionGrant.NOT_ALLOWED, "android.permission.CAMERA"),
            held(PermissionKind.POST_NOTIFICATIONS, PermissionGrant.ALLOWED, "android.permission.POST_NOTIFICATIONS"),
            held(PermissionKind.INTERNET, PermissionGrant.AT_INSTALL, "android.permission.INTERNET"),
            held(PermissionKind.BIOMETRIC, PermissionGrant.AT_INSTALL, "android.permission.USE_BIOMETRIC"),
            held(PermissionKind.BACKGROUND_WORK, PermissionGrant.AT_INSTALL, "android.permission.WAKE_LOCK"),
        ),
    )

    /** `playSafe`: the same page with no SMS row. */
    private val playSafe = smsFull.filterNot { it.kind == PermissionKind.RECEIVE_SMS }

    private val opened = mutableListOf<PermissionKind>()

    private fun capture(
        name: String,
        rows: List<PermissionRow>,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = rule.captureScreenGolden(name, fontScale, direction) {
        LfTheme { LfScaffold { DataPermissionsScreen(DataPermissionsUiState(rows), onOpen = {}, onBack = {}) } }
    }

    @Test fun smsFull_1x() = capture("data-permissions-1x", smsFull)

    @Config(qualifiers = PHONE_2X)
    @Test fun smsFull_2x() = capture("data-permissions-2x", smsFull, fontScale = 2f)

    @Test fun smsFull_1x_rtl() = capture("data-permissions-1x-rtl", smsFull, direction = LayoutDirection.Rtl)

    @Test fun playSafe_1x() = capture("data-permissions-play-safe-1x", playSafe)

    private fun show(rows: List<PermissionRow>) {
        rule.setContent {
            LfTheme { DataPermissionsScreen(DataPermissionsUiState(rows), onOpen = { opened += it }, onBack = {}) }
        }
    }

    @Test
    fun aRevocableRow_opensItsOwnSettings() {
        show(smsFull)

        rule.onNodeWithText("Camera").performScrollTo().performClick()
        rule.onNodeWithText("Notification access").performScrollTo().performClick()

        assertThat(opened).containsExactly(PermissionKind.CAMERA, PermissionKind.NOTIFICATION_ACCESS).inOrder()
    }

    /** Nowhere to send the user for these, so the row is not a button that does nothing. */
    @Test
    fun anInstallTimeRow_isNotTappable() {
        show(smsFull)

        listOf("Internet", "Fingerprint and face", "Background work").forEach { title ->
            rule.onNode(hasText(title, substring = false) and hasClickAction()).assertDoesNotExist()
        }
        val camera = rule.onNode(hasText("Camera") and hasClickAction(), useUnmergedTree = false)
        assertThat(camera.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)).isNotNull()
    }

    @Test
    fun theSentence_leadsWithTheState_thenTheReason() {
        val byKind = smsFull.associateBy { it.kind }

        assertThat(byKind.getValue(PermissionKind.CAMERA).sentence).isEqualTo("Not allowed. Scans receipts and recovery QR codes, on this phone.")
        assertThat(byKind.getValue(PermissionKind.NOTIFICATION_ACCESS).sentence).startsWith("Allowed. ")
        // The heading says "Granted when installed"; the row only says why.
        assertThat(byKind.getValue(PermissionKind.INTERNET).sentence).endsWith("LedgerFlow does not use it.")
    }

    @Test
    fun anUnexplainedPermission_isShownByItsName() {
        val row = permissionRows(
            listOf(held(PermissionKind.UNEXPLAINED, PermissionGrant.AT_INSTALL, "android.permission.READ_CONTACTS")),
        ).single()

        assertThat(row.title).isEqualTo("android.permission.READ_CONTACTS")
        assertThat(row.opensSettings).isFalse()
    }

    @Test
    fun eachRow_opensTheRightAndroidPage() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val access = permissionSettingsIntents(context, PermissionKind.NOTIFICATION_ACCESS)
        assertThat(access.first().action).isEqualTo(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        assertThat(access.last().action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)

        val notifications = permissionSettingsIntents(context, PermissionKind.POST_NOTIFICATIONS).first()
        assertThat(notifications.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(context.packageName)

        val camera = permissionSettingsIntents(context, PermissionKind.CAMERA).single()
        assertThat(camera.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(camera.data?.schemeSpecificPart).isEqualTo(context.packageName)
    }

    /** The user changes a grant in Android's settings; the page says so on the way back. */
    @Test
    fun refresh_readsThePackageAgain() {
        var camera = PermissionGrant.NOT_ALLOWED
        val vm = DataPermissionsViewModel(AppPermissions { listOf(held(PermissionKind.CAMERA, camera)) })
        assertThat(vm.state.value.rows?.single()?.sentence).startsWith("Not allowed.")

        camera = PermissionGrant.ALLOWED
        vm.refresh()

        assertThat(vm.state.value.rows?.single()?.sentence).startsWith("Allowed.")
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataPermissionsScreenDarkTest : DataPermissionsScreenTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
