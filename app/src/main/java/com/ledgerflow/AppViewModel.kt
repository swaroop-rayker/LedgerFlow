package com.ledgerflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.usecase.ObserveVaultStateUseCase
import com.ledgerflow.core.domain.usecase.OpenVaultOnLaunchUseCase
import com.ledgerflow.core.domain.usecase.PurgeAbandonedDraftsUseCase
import com.ledgerflow.core.domain.ingest.IngestWorkTrigger
import com.ledgerflow.core.domain.ingest.NotificationSetupStore
import com.ledgerflow.core.domain.usecase.SeedIngestAllowlistsUseCase
import com.ledgerflow.core.domain.usecase.SeedParserRulesUseCase
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.UpgradeBlockReason
import com.ledgerflow.core.domain.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the single Activity should be showing. */
public sealed interface AppRoute {
    public data object Loading : AppRoute
    public data object Onboarding : AppRoute
    public data class Recovery(val reason: RecoveryReason) : AppRoute
    public data object Ready : AppRoute

    /**
     * A schema migration is running (SPEC.md §8.1).
     *
     * Unlike [VaultState.Working] this **does** get its own route: the app is
     * genuinely unusable, there is no screen underneath that owns the user's
     * context, and §8.1 requires a dedicated screen rather than a spinner over
     * whatever was there.
     */
    public data class Upgrading(val from: Int, val to: Int) : AppRoute

    /** The migration did not go ahead. The database was not changed. */
    public data class UpgradeBlocked(val reason: UpgradeBlockReason) : AppRoute
}

/**
 * Whether §5.2's explainer is owed a showing before the shell (SPEC.md §5.2).
 *
 * **Three values, because "not yet known" is a real state and rendering it
 * wrongly is visible.** The answer comes from a file read that completes a few
 * milliseconds after the vault opens, and both of the other two values would be
 * a guess during that window — one flashes Home at a first-run user, the other
 * flashes a blank screen at everyone else.
 */
public enum class NotificationSetupPrompt {

    /** The flag has not been read yet. Renders whatever was already on screen. */
    Undecided,

    /** First run, or an install that predates the explainer. Show it. */
    Show,

    /** Already seen, however it was left. The shell owns the screen. */
    Dismissed,
}

/**
 * The app shell's router, driven by the vault (SPEC.md §7.3).
 *
 * It observes the vault rather than holding it: [ObserveVaultStateUseCase] is
 * the only vault-facing dependency here besides the launch attempt, so the shell
 * cannot initialise or recover a vault by accident.
 */
@HiltViewModel
public class AppViewModel @Inject constructor(
    observeVaultState: ObserveVaultStateUseCase,
    private val openVaultOnLaunch: OpenVaultOnLaunchUseCase,
    private val purgeAbandonedDrafts: PurgeAbandonedDraftsUseCase,
    private val seedIngestAllowlists: SeedIngestAllowlistsUseCase,
    private val seedParserRules: SeedParserRulesUseCase,
    private val ingestWork: IngestWorkTrigger,
    private val notificationSetup: NotificationSetupStore,
) : ViewModel() {

    private val _notificationSetupPrompt =
        MutableStateFlow(NotificationSetupPrompt.Undecided)

    /**
     * Whether the explainer should be shown before the shell (§5.2).
     *
     * **This is where §5.2's "onboarding deep-links to an explainer" actually
     * lands, and it is one step later than the sentence suggests.** §7.4's gate
     * cannot host it: `completeBackupLocation` is where the vault is *created*,
     * so the route switches away from onboarding at the same instant the last
     * gate step completes, and a step after it would be rendering while the app
     * had already moved on. Putting a *declinable* step inside a gate whose
     * whole design is that nothing in it can be skipped is also the wrong shape
     * — notification access can always be refused, and the gate's steps cannot.
     *
     * So the explainer is the first thing shown once the vault exists, which is
     * first run by any reading a user would recognise, and the §7.4 gate is
     * untouched.
     */
    public val notificationSetupPrompt: StateFlow<NotificationSetupPrompt> =
        _notificationSetupPrompt.asStateFlow()

    /**
     * The user left the explainer.
     *
     * Only the in-memory value: the durable flag is written by the explainer's
     * own ViewModel when it handles `Done`, which is the screen that knows the
     * user actually saw it. Doing it in both places would be two writers for one
     * fact, and this one would fire on a path where the screen was never
     * composed.
     */
    public fun dismissNotificationSetup() {
        _notificationSetupPrompt.value = NotificationSetupPrompt.Dismissed
    }

    /**
     * [VaultState.Working] deliberately does not map to a route.
     *
     * It occurs *inside* onboarding (creating the DEK and database) and inside
     * recovery (running the KDF). Both screens show their own progress, and both
     * own the words the user is mid-way through. Swapping either out for a
     * generic spinner would throw away that context and, on the onboarding path,
     * briefly replace "Setting up your ledger" with a blank screen. So Working
     * holds the previous route and the screen underneath keeps rendering.
     */
    public val route: StateFlow<AppRoute> = observeVaultState()
        .scan<VaultState, AppRoute>(AppRoute.Loading) { previous, state ->
            when (state) {
                VaultState.Initializing -> AppRoute.Loading
                VaultState.NeedsOnboarding -> AppRoute.Onboarding
                VaultState.Unlocked -> AppRoute.Ready
                is VaultState.NeedsRecovery -> AppRoute.Recovery(state.reason)
                is VaultState.Upgrading -> AppRoute.Upgrading(state.from, state.to)
                is VaultState.UpgradeBlocked -> AppRoute.UpgradeBlocked(state.reason)
                VaultState.Working -> previous
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppRoute.Loading)

    init {
        // §7.3 step 1. Idempotent, so a config change does not re-open anything.
        viewModelScope.launch { openVaultOnLaunch() }

        // §6.1.2's orphan sweep: drafts the user abandoned 30 days ago, from
        // launches where the app was killed and they never came back.
        //
        // It waits for the vault rather than running at construction, because
        // there is no database until the unlock succeeds -- and it deliberately
        // runs after *any* route reaches Ready, so a user who came in through
        // Recovery gets the same housekeeping as one who came in through the
        // Keystore. `first` cancels the collection as soon as it fires, so this
        // is one sweep per process, not a subscription.
        viewModelScope.launch {
            route.first { it is AppRoute.Ready }
            purgeAbandonedDrafts()

            // D-10's curated allowlists, once the vault is open (they live in
            // it). Idempotent and additive, so this runs every launch and a
            // package the user disabled stays disabled -- see
            // SeedIngestAllowlistsUseCase. It waits for Ready for the same
            // reason the draft sweep does: there is no database before that.
            seedIngestAllowlists()

            // The ruleset lives in the vault beside the allowlists, and for the
            // same reason: there is nothing to write to until it opens.
            seedParserRules()

            // Both of the above can change what the pipeline would now make of
            // messages it has already seen -- a seed that adds sender patterns
            // (§16 Q14) or parser rules. Nothing else would ask: the worker is
            // otherwise only enqueued by a capture, so on a quiet account the
            // fix for a wrongly-rejected message could sit unrun for days. The
            // pass is idempotent and collapses under KEEP, so asking on every
            // launch costs a query that finds nothing.
            ingestWork.requestParsePass()

            // §5.2's explainer, decided once the vault is open -- the same
            // Ready gate everything else in this block waits for, though for a
            // different reason: the flag lives outside the vault (ADR-0020) and
            // could have been read earlier. It is read here so that the value
            // cannot arrive before there is a screen it could change.
            _notificationSetupPrompt.value = if (notificationSetup.hasSeenSetup()) {
                NotificationSetupPrompt.Dismissed
            } else {
                NotificationSetupPrompt.Show
            }
        }
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
