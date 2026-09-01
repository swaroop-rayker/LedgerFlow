package com.ledgerflow.feature.onboarding.notifications

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The explainer, wired (SPEC.md §5.2).
 *
 * Three things live here rather than in [NotificationAccessViewModel] or
 * [NotificationAccessScreen], and each for the same reason: they are Android
 * interactions that need an `Activity`-shaped context, which a stateless
 * composable must not reach for and a ViewModel must not hold.
 *
 * 1. **The Settings deep link.** §5.2's `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
 * 2. **The `POST_NOTIFICATIONS` prompt**, which replaces the bare one-shot
 *    request that sat at `AppRoute.Ready` through P2-7. That was explicitly the
 *    minimum rather than the UX; this is what it was waiting for.
 * 3. **The resume poll.** §5.2: "polls `getEnabledListenerPackages()` on resume
 *    to confirm". The user leaves for a system page in another task and comes
 *    back, and this is the only moment the app can learn what they did.
 *
 * One route serves both hosts — the first-run presentation and the Settings
 * destination — differing only in [doneLabel] and what [onDone] does after the
 * screen records itself as seen.
 */
@Composable
public fun NotificationAccessRoute(
    onDone: () -> Unit,
    doneLabel: String,
    modifier: Modifier = Modifier,
) {
    val viewModel: NotificationAccessViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The confirmation half of §5.2's grant flow. Fires on the resume that
    // follows a trip to system settings, and on every later one -- the grant can
    // also be revoked while the app is backgrounded, and this screen should
    // report that just as promptly.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The result is ignored on purpose: denying is a legitimate answer, and
        // the row's chip is driven by the poll below rather than by this
        // callback, so the two can never disagree.
        viewModel.refresh()
    }

    NotificationAccessScreen(
        state = state,
        doneLabel = doneLabel,
        modifier = modifier,
        onEvent = { event ->
            when (event) {
                NotificationAccessEvent.OpenListenerSettings -> context.openListenerSettings()

                NotificationAccessEvent.RequestPostNotifications -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                NotificationAccessEvent.Done -> {
                    viewModel.onEvent(event)
                    onDone()
                }
            }
        },
    )
}

/**
 * §5.2's deep link.
 *
 * `NEW_TASK` because the caller may be an Activity or, on some OEM builds, a
 * context that is not one; the flag is harmless in the first case and required
 * in the second.
 *
 * **The failure is caught rather than allowed to crash.** A handful of OEM ROMs
 * and every device with the Settings app disabled have no activity for this
 * action, and an uncaught `ActivityNotFoundException` would take down the app
 * from a button whose entire purpose is to help. The fallback is the app's own
 * notification settings page, which on those devices is where the listener
 * toggle actually lives; if that is missing too, the screen simply stays put and
 * the user has lost a tap rather than their session.
 */
private fun android.content.Context.openListenerSettings() {
    val actions = listOf(
        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
    )
    for (action in actions) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
}
