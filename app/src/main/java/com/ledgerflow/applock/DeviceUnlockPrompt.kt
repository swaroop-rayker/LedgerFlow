package com.ledgerflow.applock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The phone's own unlock, asked for the app lock (SPEC §7.6; platform SDK
 * only, owner 2026-10-24 -- no `androidx.biometric`).
 *
 * - **Android 11+**: `BiometricPrompt` allowing biometrics *or* the phone's PIN,
 *   pattern or password, so a user whose fingerprint stops working still gets
 *   in. Weak biometrics are accepted: this lifts a screen, it does not release
 *   a key (nothing here touches the Keystore).
 * - **Android 10**: the same prompt through its older device-credential flag.
 * - **Android 8-9**: `KeyguardManager`'s confirm-credential screen, a separate
 *   activity, which is why it needs [launcher].
 *
 * Construct in `onCreate`: the launcher must be registered before the activity
 * starts.
 */
internal class DeviceUnlockPrompt(
    private val activity: ComponentActivity,
    private val onUnlocked: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) onUnlocked() else onClosed()
        }

    fun show() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> biometricPrompt(withCredentialFlag = false)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> biometricPrompt(withCredentialFlag = true)
            else -> confirmCredential()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun biometricPrompt(withCredentialFlag: Boolean) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(TITLE)
            .setSubtitle(SUBTITLE)
        if (withCredentialFlag) {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onClosed()
            },
        )
    }

    private fun confirmCredential() {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION") // The only device-credential prompt below API 29.
        val intent = keyguard?.createConfirmDeviceCredentialIntent(TITLE, SUBTITLE)
        if (intent == null) {
            // No screen lock: the controller does not lock without one, so
            // there is nothing to prove.
            onUnlocked()
        } else {
            launcher.launch(intent)
        }
    }

    private companion object {
        const val TITLE = "Unlock LedgerFlow"
        const val SUBTITLE = "Use your phone's fingerprint, face or screen lock"
    }
}
