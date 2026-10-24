package com.ledgerflow

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerflow.appearance.AppMotionDurationScale
import com.ledgerflow.appearance.AppearanceViewModel
import com.ledgerflow.appearance.RegionConventionsSync
import com.ledgerflow.applock.AppLockController
import com.ledgerflow.applock.DeviceUnlockPrompt
import com.ledgerflow.applock.LockScreen
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.MotionLevel
import com.ledgerflow.core.domain.appearance.PhoneMotionSettings
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.feature.onboarding.OnboardingScreen
import com.ledgerflow.feature.onboarding.OnboardingViewModel
import com.ledgerflow.feature.onboarding.notifications.NotificationAccessRoute
import com.ledgerflow.feature.onboarding.recovery.RecoveryScreen
import com.ledgerflow.feature.onboarding.recovery.RecoveryViewModel
import com.ledgerflow.feature.onboarding.restore.RestoreRoute
import com.ledgerflow.feature.onboarding.upgrade.UpgradeBlockedScreen
import com.ledgerflow.feature.onboarding.upgrade.UpgradingScreen
import com.ledgerflow.navigation.LedgerFlowShell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single Activity (SPEC.md §9.3).
 *
 * `enableEdgeToEdge()` is called before `setContent` and stays there. Android
 * 15+ enforces edge-to-edge anyway; calling it explicitly means the behaviour
 * is the same on every API level we support rather than changing under us at
 * 35. Every screen consumes `WindowInsets.safeDrawing` through `LfScaffold`,
 * which is BUG5's countermeasure -- retrofitting insets later is the expensive
 * path.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    /**
     * The deep link waiting to be honoured (SPEC.md §5.1). P2-7.
     *
     * **Held rather than handled, because the nav graph may not exist yet.**
     * `LedgerFlowApp` gates the whole graph behind [AppRoute.Ready], so a
     * notification tap on a cold start arrives while the vault is still
     * opening and there is nothing to navigate. Navigation Compose's own
     * `navDeepLink` reads the Activity's intent when the graph is set, which
     * would happen to work for that case and silently not for the others —
     * this is explicit instead, and covers a warm tap through [onNewIntent] as
     * well.
     *
     * Cleared once the shell has acted on it, so a configuration change does
     * not re-navigate the user to a candidate they have already reviewed.
     */
    private val deepLink = MutableStateFlow<String?>(null)

    private val appearance: AppearanceViewModel by viewModels()

    /** The phone's "Animator duration scale", re-read on every resume. */
    @Inject
    internal lateinit var phoneMotion: PhoneMotionSettings

    /** Settings > Currency & region, kept in `DisplayConventions` by the Application. */
    @Inject
    internal lateinit var regionConventions: RegionConventionsSync

    /** Every Compose animation's scale; zero under Animations > Off (V2-3). */
    private val motionScale = AppMotionDurationScale()

    /** The app lock (SPEC §7.6): a screen gate, decided for the whole process. */
    @Inject
    internal lateinit var appLock: AppLockController

    /** The phone's own unlock prompt; registered in [onCreate], before the activity starts. */
    private lateinit var unlockPrompt: DeviceUnlockPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.dataString
        unlockPrompt = DeviceUnlockPrompt(
            activity = this,
            onUnlocked = { appLock.unlocked() },
            onClosed = { appLock.promptClosed() },
        )

        // The window's recomposer, made here rather than by Compose so that it
        // plays animations at [motionScale] instead of the scale Compose would
        // read from the phone. It is otherwise the one Compose makes: tied to
        // this activity's lifecycle, cancelled when the window goes. Dialogs,
        // sheets and popups compose under it, so Off reaches them too.
        val recomposer = window.decorView.createLifecycleAwareWindowRecomposer(
            coroutineContext = motionScale,
            lifecycle = lifecycle,
        )

        setContent(parent = recomposer) {
            // Nothing until the choices are read (AppearanceViewModel): a few
            // milliseconds of the launch window, never a frame in the wrong theme.
            val prefs = appearance.preferences.collectAsStateWithLifecycle().value ?: return@setContent
            // And Currency & region, applied process-wide by the Application:
            // the first amount drawn is already grouped as the user chose.
            if (!regionConventions.ready.collectAsStateWithLifecycle().value) return@setContent
            // And the lock's decision (SPEC §7.6): never a frame of content
            // that the lock is about to cover.
            val security = appLock.preferences.collectAsStateWithLifecycle().value ?: return@setContent
            val locked = appLock.locked.collectAsStateWithLifecycle().value ?: return@setContent
            WindowPrivacy(blockScreenshots = security.blockScreenshots, appLockOn = security.appLock)
            val dark = prefs.theme.isDark(phoneIsDark = isSystemInDarkTheme())
            val motion = rememberMotionLevel(prefs.animations)
            SystemBarIconsFollow(dark)
            LfTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    // Still composed under the lock, so the user returns to
                    // where they were; hidden from accessibility services
                    // while locked, so a screen reader cannot read past it.
                    Box(if (locked) Modifier.clearAndSetSemantics { } else Modifier) {
                        LedgerFlowApp(
                            deepLink = deepLink.collectAsStateWithLifecycle().value,
                            onDeepLinkHandled = { deepLink.value = null },
                            motionLevel = motion,
                            startScreen = prefs.startScreen,
                        )
                    }
                    if (locked) {
                        LockScreen(onUnlock = ::askToUnlock, onLeave = { moveTaskToBack(true) })
                    }
                }
            }
        }
    }

    /** Opens the phone's prompt, once at a time (SPEC §7.6). */
    private fun askToUnlock() {
        if (appLock.isAuthenticating) return
        appLock.promptShown()
        unlockPrompt.show()
    }

    /**
     * Settings > Security & privacy's window flags (V2-PLAN §3.5).
     *
     * **Block screenshots** is `FLAG_SECURE`: no screenshots or recordings, and
     * a blank window in screen sharing and in Recents. Compose's dialogs and
     * sheets inherit it from this window (their default secure policy).
     *
     * **With the app lock on**, Recents shows no picture of the app on Android
     * 13+ even when screenshots are allowed: a thumbnail of the last screen
     * would show exactly what the lock is there to cover.
     */
    @Composable
    private fun WindowPrivacy(blockScreenshots: Boolean, appLockOn: Boolean) {
        DisposableEffect(blockScreenshots) {
            if (blockScreenshots) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose { }
        }
        DisposableEffect(appLockOn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!appLockOn)
            onDispose { }
        }
    }

    /**
     * Animations > Full / Reduced / Off, with the phone's "Remove animations"
     * applied on top (§9.5: the phone's switch always wins). The phone's scale
     * is re-read on resume -- the switch lives in another app, so coming back is
     * the only moment it can have changed -- and pushed into [motionScale].
     */
    @Composable
    private fun rememberMotionLevel(choice: AnimationPreference): MotionLevel {
        var phoneScale by remember { mutableFloatStateOf(phoneMotion.animatorDurationScale()) }
        LifecycleResumeEffect(Unit) {
            phoneScale = phoneMotion.animatorDurationScale()
            onPauseOrDispose { }
        }
        val level = choice.motionLevel(phoneAnimationsOn = phoneScale != 0f)
        SideEffect { motionScale.update(level, phoneScale) }
        return level
    }

    /**
     * Status- and navigation-bar icons drawn for the app's theme, not the
     * phone's. `enableEdgeToEdge()` picks them from the phone's night mode, so
     * with Appearance set against the phone (Dark on a light phone) the clock
     * and battery would be dark on a dark screen. Re-applied whenever the
     * resolved theme changes; the scrims are the library's own defaults.
     */
    @Composable
    private fun SystemBarIconsFollow(dark: Boolean) {
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    lightScrim = android.graphics.Color.TRANSPARENT,
                    darkScrim = android.graphics.Color.TRANSPARENT,
                ) { dark },
                navigationBarStyle = SystemBarStyle.auto(
                    lightScrim = NAV_BAR_LIGHT_SCRIM,
                    darkScrim = NAV_BAR_DARK_SCRIM,
                ) { dark },
            )
            onDispose { }
        }
    }

    private companion object {
        /** `androidx.activity`'s `DefaultLightScrim`, which it keeps private. */
        private val NAV_BAR_LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)

        /** `androidx.activity`'s `DefaultDarkScrim`. */
        private val NAV_BAR_DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    /**
     * A tap while the app is already running.
     *
     * `singleTop` in the manifest is what routes it here instead of stacking a
     * second Activity. `setIntent` keeps `getIntent()` honest for anything that
     * reads it later; without it the Activity would keep reporting the intent
     * it was originally launched with.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.dataString
    }
}

/**
 * The vault decides what is on screen (SPEC.md §7.3).
 *
 * There is no route to the ledger that does not pass through an unlocked vault,
 * and no branch here that destroys anything: a failure to unlock lands on
 * [RecoveryScreen], which is a screen the user acts on.
 *
 * Navigation Compose and the bottom bar land in the next step; until then
 * [AppRoute.Ready] renders a placeholder rather than a half-built nav graph.
 */
@Composable
private fun LedgerFlowApp(
    deepLink: String?,
    onDeepLinkHandled: () -> Unit,
    motionLevel: MotionLevel,
    startScreen: StartScreen,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val route by appViewModel.route.collectAsStateWithLifecycle()

    when (val current = route) {
        AppRoute.Loading -> LoadingScreen()

        AppRoute.Onboarding -> {
            // Which of two screens, not business state: the restore branch
            // (§16 Q11) is a fork off onboarding's first screen, and back
            // returns to it. Saveable so a rotation does not drop the user
            // out of the restore they were in.
            var restoring by rememberSaveable { mutableStateOf(false) }
            if (restoring) {
                RestoreRoute(resuming = false, onBack = { restoring = false })
            } else {
                val viewModel: OnboardingViewModel = hiltViewModel()
                // collectAsStateWithLifecycle, never bare collectAsState
                // (CLAUDE.md §5): the latter keeps collecting while backgrounded.
                val state by viewModel.state.collectAsStateWithLifecycle()
                OnboardingScreen(
                    state = state,
                    onEvent = viewModel::onEvent,
                    onGeneratePhrase = viewModel::generatePhraseAndContinue,
                    kitFileName = viewModel::suggestedKitFileName,
                    onRestoreRequested = { restoring = true },
                )
            }
        }

        // The same screen and ViewModel as the branch above, with no way back:
        // an interrupted restore is finished, not abandoned into onboarding.
        AppRoute.RestoreInterrupted -> RestoreRoute(resuming = true, onBack = null)

        is AppRoute.Recovery -> {
            val viewModel: RecoveryViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(current.reason) { viewModel.setReason(current.reason) }
            RecoveryScreen(state = state, onEvent = viewModel::onEvent)
        }

        // §8.1: a dedicated screen that owns the upgrade, not a spinner over
        // whatever happened to be showing.
        is AppRoute.Upgrading -> UpgradingScreen(from = current.from, to = current.to)

        is AppRoute.UpgradeBlocked -> UpgradeBlockedScreen(reason = current.reason)

        AppRoute.Ready -> {
            val prompt by appViewModel.notificationSetupPrompt.collectAsStateWithLifecycle()
            ReadyRoute(
                deepLink = deepLink,
                prompt = prompt,
                explainer = {
                    NotificationAccessRoute(
                        onDone = appViewModel::dismissNotificationSetup,
                        // Not "Done": nothing has necessarily been done. This is
                        // the one presentation the user did not ask for, so the
                        // label has to make declining an obvious, unembarrassed
                        // option -- a "Done" on an ungranted screen reads as the
                        // app pretending the user complied.
                        doneLabel = "Not now",
                    )
                },
                shell = {
                    LedgerFlowShell(
                        deepLink = deepLink,
                        onDeepLinkHandled = onDeepLinkHandled,
                        motionLevel = motionLevel,
                        startScreen = startScreen,
                    )
                },
            )
        }
    }
}

/**
 * What an unlocked vault shows: §5.2's explainer or the shell.
 *
 * **The shell has exactly one call site** (BUG59). It used to be hosted from
 * two `when` branches, "a link is waiting" and "everything else", and the shell
 * clears the link once it has navigated. Clearing it moved the shell from one
 * branch to the other, which to Compose is a different place in the tree, so
 * the shell and its nav controller were thrown away and rebuilt on Home: every
 * Inbox link opened the Inbox for a frame and then left the user on Home.
 */
@Composable
internal fun ReadyRoute(
    deepLink: String?,
    prompt: NotificationSetupPrompt,
    explainer: @Composable () -> Unit,
    shell: @Composable () -> Unit,
) {
    // A tap on an Inbox notification names a specific candidate, and it
    // outranks the explainer unconditionally. Swallowing a deep link to show a
    // setup screen would answer a user who asked for one thing with an
    // unrelated one, and the explainer has two standing routes back while the
    // tap has none. Remembered, because the shell clears the link as soon as
    // it has acted on it: "outranks" has to outlast that, or the explainer
    // takes the screen from the Inbox the user just opened. It is still shown
    // on the next launch, since the prompt stays `Show` until dismissed.
    var openedByLink by rememberSaveable { mutableStateOf(false) }
    if (deepLink != null && !openedByLink) openedByLink = true

    // §5.2's explainer, at the first moment there is a vault behind it. See
    // AppViewModel.notificationSetupPrompt for why this is here and not inside
    // §7.4's gate. Undecided renders the shell rather than a spinner: the flag
    // read is a single small file and the alternative is a blank frame on every
    // launch of an install that has already seen it, which is all of them after
    // the first.
    if (prompt == NotificationSetupPrompt.Show && !openedByLink) explainer() else shell()
}

@Composable
private fun LoadingScreen() {
    LfScaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Text(
                text = "Unlocking",
                style = LfTheme.typography.titleM,
                color = LfTheme.colors.textPrimary,
            )
        }
    }
}

