package com.ledgerflow

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt's object-graph root.
 *
 * WorkManager configuration and the crash handler arrive with the steps that
 * introduce them. What is here now is the `@HiltAndroidApp` trigger and
 * StrictMode.
 */
@HiltAndroidApp
public class LedgerFlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) enableStrictMode()
    }

    /**
     * BUG7's tripwire, debug builds only.
     *
     * It guards the thing that matters most in this app: the unlock flow does
     * file I/O, 2048 rounds of PBKDF2 and a SQLCipher database open, and every
     * one of those must stay on the injected IO dispatcher. Drift back onto the
     * main thread should kill the debug build immediately rather than ship an
     * ANR to a real device.
     *
     * **`penaltyDeath()` is deliberately not used, and this is not a softening.**
     * A bare `penaltyDeath` makes the app unusable on this project's own test
     * device: Samsung's framework reads from disk on the main thread during
     * every `handleResumeActivity`, in code we neither call nor can avoid --
     *
     * ```
     * DiskReadViolation
     *   at android.app.ContextImpl.deleteSharedPreferences
     *   at android.app.IdsController.openIdsWindow
     *   at android.app.ActivityThread.handleResumeActivity
     * ```
     *
     * A tripwire that fires on someone else's code is a tripwire people switch
     * off, which would cost us the whole check. So the policy logs everything
     * and a listener re-throws only violations whose stack actually contains
     * LedgerFlow frames -- strictly *more* precise than `penaltyDeath`, not less
     * strict, because our own main-thread I/O still dies on the spot.
     *
     * `penaltyListener` needs API 28; below that the violations are logged and
     * the emulator/CI matrix at API 26 is where that gap gets covered.
     */
    private fun enableStrictMode() {
        val threadPolicy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            threadPolicy.penaltyListener(mainExecutor) { violation ->
                if (violation.isOurs()) throw violation
            }
        }
        StrictMode.setThreadPolicy(threadPolicy.build())

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    private fun Violation.isOurs(): Boolean =
        stackTrace.any { it.className.startsWith(PACKAGE_PREFIX) }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        private const val PACKAGE_PREFIX = "com.ledgerflow"
    }
}
