package com.ledgerflow.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.analytics.BudgetAlertChannel
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.AppearancePreferences
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import com.ledgerflow.core.domain.ingest.NotificationCaptureHealth
import com.ledgerflow.core.domain.ingest.PackageAllowlistRepository
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.security.HideAmountsSetting
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.domain.usecase.GetNotificationCaptureHealthUseCase
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.core.model.RegionConventions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Settings tab asks its ViewModel to do. */
public sealed interface SettingsEvent {

    /** Opens the confirmation. Does not delete anything by itself. */
    public data object ReceiptDeleteRequested : SettingsEvent

    /** Irreversible (ADR-0023). */
    public data object ReceiptDeleteConfirmed : SettingsEvent

    public data object ReceiptDeleteDismissed : SettingsEvent

    /** Appearance (V2-3). Applies at once; the activity follows the store. */
    public sealed interface AppearanceEvent : SettingsEvent

    public data class ThemePicked(val theme: ThemePreference) : AppearanceEvent

    public data class AnimationsPicked(val animations: AnimationPreference) : AppearanceEvent

    /** Kept now, applied from the next launch (the shell reads it once). */
    public data class StartScreenPicked(val startScreen: StartScreen) : AppearanceEvent

    /** Currency & region (V2-3). Applies to every amount as it is next drawn. */
    public sealed interface RegionEvent : SettingsEvent

    public data class GroupingPicked(val grouping: NumberGrouping) : RegionEvent

    /** Opens the date-order picker. */
    public data object DateOrderRequested : RegionEvent

    /** Applies the order and closes the picker. */
    public data class DateOrderPicked(val order: DateOrder) : RegionEvent

    public data object DateOrderDismissed : RegionEvent

    /** Security & privacy (V2-3, SPEC §7.6). Turning the lock on needs a phone screen lock. */
    public sealed interface SecurityEvent : SettingsEvent

    public data class AppLockToggled(val enabled: Boolean) : SecurityEvent

    public data object LockAfterRequested : SecurityEvent

    public data class LockAfterPicked(val timeout: LockTimeout) : SecurityEvent

    public data object LockAfterDismissed : SecurityEvent

    public data class BlockScreenshotsToggled(val enabled: Boolean) : SecurityEvent

    public data class HideAmountsToggled(val enabled: Boolean) : SecurityEvent
}

/**
 * The Settings tab's state (SPEC.md §9.3; it was the More tab until V2-2).
 *
 * It had no ViewModel until the bin arrived, because until then every row on it
 * was a static label over a navigation callback. This one still is a navigation
 * callback — but its subtitle has to say how much is in the bin, and that is a
 * question only the database can answer.
 *
 * Everything that *acts* on the bin lives in the bin (ADR-0015). This screen
 * counts and points.
 */
@Immutable
public data class SettingsUiState(
    /**
     * Deleted entries across **both** books.
     *
     * Summed for display only. Nothing is netted and nothing is compared — a
     * count of rows is not a monetary figure, so Law 2 has no opinion about it,
     * and the underlying queries are still one per book.
     */
    val deletedCount: Int = 0,

    /**
     * Whether the count has been read yet.
     *
     * Zero and "not asked" would otherwise render the same subtitle, and one of
     * them is a lie for the fraction of a second before the query returns.
     */
    val isLoaded: Boolean = false,

    /**
     * Receipt images on disk (ADR-0023).
     *
     * The ADR declines a timed purge and makes growth **visible** instead, so
     * this row is the whole of that decision's user-facing half — and, until
     * a viewer exists, the only way to remove an image at all.
     */
    val receipts: AttachmentUsage = AttachmentUsage(count = 0, bytes = 0L),

    /** True while the irreversible confirmation is up. */
    val confirmingReceiptDelete: Boolean = false,

    /**
     * Whether notification capture is working (SPEC.md §5.2).
     *
     * The row it drives is the standing route to the permission explainer, and
     * the reason it carries a status rather than a static label is the same
     * lesson [deletedSubtitle] records one row up: a Settings row that says
     * nothing about its own state is one people open to find out, and a user who
     * suspects capture is broken looks here first.
     *
     * Polled on resume like every other reader of this value — the grant lives
     * in system Settings and changes without telling anyone.
     */
    val captureHealth: NotificationCaptureHealth = NotificationCaptureHealth.RECONNECTING,

    /**
     * When the last **verified** backup was written, or null if never
     * (§16 Q23). Only a backup that passed its read-back check records this,
     * so the row never reassures on the strength of a failed one.
     */
    val lastBackupAt: Long? = null,

    /**
     * Nightly backups are enrolled (ADR-0027): this phone backs up on its own,
     * and still cannot open a backup.
     */
    val nightlyBackupsEnabled: Boolean = false,

    /** Settings > Appearance (V2-PLAN §3.2), as stored outside the vault (ADR-0030). */
    val appearance: AppearancePreferences = AppearancePreferences(),

    /**
     * False when the phone has "Remove animations" on, which overrides the
     * app's choice (§9.5). Read on resume, with capture health.
     */
    val phoneAnimationsOn: Boolean = true,

    /**
     * The install's base currency (`app_meta`, §5.8), or null until read. Shown
     * read-only (D4) and used for the grouping row's sample amount.
     */
    val baseCurrency: String? = null,

    /** Settings > Currency & region (V2-PLAN §3.3), stored outside the vault (ADR-0030). */
    val region: RegionConventions = RegionConventions(),

    /** True while the date-order picker is up. */
    val choosingDateOrder: Boolean = false,

    /**
     * Whether budget alerts can reach the user: Android's "Budget alerts" channel
     * and the app's notifications (V2-PLAN §3.4). Null until read; re-read on
     * resume, as the user changes it in system settings.
     */
    val budgetAlerts: AlertChannelState? = null,

    /** Apps whose notifications are read (the enabled allowlist, D-10); null until read. */
    val paymentApps: Int? = null,

    /** Settings > Security & privacy (SPEC §7.6, V2-PLAN §3.5), outside the vault (ADR-0030). */
    val security: SecurityPreferences = SecurityPreferences(),

    /** The phone has a PIN, pattern or password; without one the app lock cannot be turned on. */
    val hasScreenLock: Boolean = true,

    /** True while the lock-after picker is up. */
    val choosingLockAfter: Boolean = false,

    /** Hide amounts on Home (V2-PLAN §3.5), kept in the vault. */
    val hideAmounts: Boolean = false,
)

@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    private val getCaptureHealth: GetNotificationCaptureHealthUseCase,
    private val attachments: AttachmentRepository,
    backups: BackupRepository,
    device: DevicePreferences,
    private val budgetAlertChannel: BudgetAlertChannel,
    allowlist: PackageAllowlistRepository,
    private val hideAmounts: HideAmountsSetting,
) : ViewModel() {

    private val appearanceStore = device.appearance
    private val phoneMotion = device.phoneMotion
    private val regionStore = device.region
    private val securityStore = device.security
    private val deviceSecurity = device.deviceSecurity

    private val phoneAnimationsOn = MutableStateFlow(true)

    private val baseCurrency = MutableStateFlow<String?>(null)

    private val choosingDateOrder = MutableStateFlow(false)

    private val budgetAlerts = MutableStateFlow<AlertChannelState?>(null)

    private val hasScreenLock = MutableStateFlow(true)

    private val choosingLockAfter = MutableStateFlow(false)

    /**
     * The polled half.
     *
     * A `MutableStateFlow` folded into the combine below rather than a second
     * `StateFlow` the screen also collects: one screen gets one state object
     * (CLAUDE.md §5), and two flows would let the row's label and its subtitle
     * recompose out of step.
     */
    private val captureHealth =
        MutableStateFlow(NotificationCaptureHealth.RECONNECTING)

    /**
     * One flow per book, combined here rather than in a query.
     *
     * There is deliberately no count that answers for both: a statement
     * spanning the two books is the shape ADR-0002 removes, and a screen adding
     * two numbers together is not the same thing as a database doing it.
     */
    /**
     * Receipt storage, polled rather than observed.
     *
     * It is a measurement of the *filesystem*, not a query, so there is no
     * `Flow` to collect — and it changes only when the user scans or deletes,
     * both of which already pass through [refresh] or [onEvent].
     */
    private val receipts = MutableStateFlow(AttachmentUsage(count = 0, bytes = 0L))

    private val confirmingDelete = MutableStateFlow(false)

    public val state: StateFlow<SettingsUiState> = combine(
        ledger.observeDeletedCount(LedgerType.DEBIT),
        ledger.observeDeletedCount(LedgerType.CREDIT),
        captureHealth,
        receipts,
        confirmingDelete,
    ) { debits, credits, health, images, confirming ->
        SettingsUiState(
            deletedCount = debits + credits,
            isLoaded = true,
            captureHealth = health,
            receipts = images,
            confirmingReceiptDelete = confirming,
        )
    }
        // Joined second rather than as a sixth argument: `combine` is typed
        // to five, and the array form would give up the types.
        .combine(backups.lastBackupAt()) { state, last -> state.copy(lastBackupAt = last) }
        .combine(backups.nightlyBackupsEnabled()) { state, nightly ->
            state.copy(nightlyBackupsEnabled = nightly)
        }
        .combine(appearanceStore.preferences) { state, appearance -> state.copy(appearance = appearance) }
        .combine(phoneAnimationsOn) { state, on -> state.copy(phoneAnimationsOn = on) }
        .combine(regionStore.conventions) { state, region -> state.copy(region = region) }
        .combine(baseCurrency) { state, code -> state.copy(baseCurrency = code) }
        .combine(choosingDateOrder) { state, choosing -> state.copy(choosingDateOrder = choosing) }
        .combine(budgetAlerts) { state, alerts -> state.copy(budgetAlerts = alerts) }
        .combine(allowlist.observe()) { state, apps -> state.copy(paymentApps = apps.count { it.enabled }) }
        .combine(securityStore.preferences) { state, security -> state.copy(security = security) }
        .combine(hasScreenLock) { state, secure -> state.copy(hasScreenLock = secure) }
        .combine(choosingLockAfter) { state, choosing -> state.copy(choosingLockAfter = choosing) }
        .combine(hideAmounts.enabled.onStart { emit(false) }) { state, hide -> state.copy(hideAmounts = hide) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    init {
        refresh()
    }

    /** §5.2's resume poll. Called from the screen, for the reason its route explains. */
    public fun refresh() {
        // The phone's switch lives in the system's Settings app, so returning
        // from it is a resume -- the same moment capture health is re-read.
        phoneAnimationsOn.value = phoneMotion.animatorDurationScale() != 0f
        // Likewise the notification channel: the user turns it on in Android's
        // settings and comes back.
        budgetAlerts.value = budgetAlertChannel.state()
        hasScreenLock.value = deviceSecurity.hasScreenLock()
        viewModelScope.launch {
            captureHealth.value = getCaptureHealth()
            receipts.value = attachments.usage()
            // Fixed once set (D4), but read here rather than once: it is a
            // vault read, and the vault is open by the time Settings exists.
            baseCurrency.value = ledger.baseCurrency()
        }
    }

    public fun onEvent(event: SettingsEvent) {
        when (event) {
            // The row opens the confirmation rather than doing anything, even
            // at zero: a destructive control that is sometimes inert teaches
            // people to tap it without reading.
            SettingsEvent.ReceiptDeleteRequested -> confirmingDelete.value = true

            SettingsEvent.ReceiptDeleteDismissed -> confirmingDelete.value = false

            SettingsEvent.ReceiptDeleteConfirmed -> viewModelScope.launch {
                attachments.deleteAll()
                confirmingDelete.value = false
                // Re-measured rather than assumed to be zero: a failed unlink
                // must show as a file still there, not as a clean slate the
                // app merely hoped for.
                receipts.value = attachments.usage()
            }

            is SettingsEvent.AppearanceEvent -> onAppearance(event)

            is SettingsEvent.RegionEvent -> onRegion(event)

            is SettingsEvent.SecurityEvent -> onSecurity(event)
        }
    }

    // Each written, then read back through its store's flow, so a control shows
    // what was kept rather than what was tapped.
    private fun onAppearance(event: SettingsEvent.AppearanceEvent) {
        viewModelScope.launch {
            when (event) {
                is SettingsEvent.ThemePicked -> appearanceStore.setTheme(event.theme)
                is SettingsEvent.AnimationsPicked -> appearanceStore.setAnimations(event.animations)
                is SettingsEvent.StartScreenPicked -> appearanceStore.setStartScreen(event.startScreen)
            }
        }
    }

    private fun onRegion(event: SettingsEvent.RegionEvent) {
        when (event) {
            is SettingsEvent.GroupingPicked -> viewModelScope.launch { regionStore.setGrouping(event.grouping) }
            SettingsEvent.DateOrderRequested -> choosingDateOrder.value = true
            SettingsEvent.DateOrderDismissed -> choosingDateOrder.value = false
            is SettingsEvent.DateOrderPicked -> {
                choosingDateOrder.value = false
                viewModelScope.launch { regionStore.setDateOrder(event.order) }
            }
        }
    }

    private fun onSecurity(event: SettingsEvent.SecurityEvent) {
        when (event) {
            is SettingsEvent.AppLockToggled -> setAppLock(event.enabled)
            SettingsEvent.LockAfterRequested -> choosingLockAfter.value = true
            SettingsEvent.LockAfterDismissed -> choosingLockAfter.value = false
            is SettingsEvent.LockAfterPicked -> {
                choosingLockAfter.value = false
                viewModelScope.launch { securityStore.setLockAfter(event.timeout) }
            }
            is SettingsEvent.BlockScreenshotsToggled ->
                viewModelScope.launch { securityStore.setBlockScreenshots(event.enabled) }
            is SettingsEvent.HideAmountsToggled ->
                viewModelScope.launch { hideAmounts.set(event.enabled) }
        }
    }

    /**
     * Turning the lock on is refused without a phone screen lock: the phone's
     * prompt is what lifts it, and a lock nothing can lift would strand the
     * user. The row's sentence says so. Turning it off is never refused.
     */
    private fun setAppLock(enabled: Boolean) {
        val secure = deviceSecurity.hasScreenLock()
        hasScreenLock.value = secure
        if (enabled && !secure) return
        viewModelScope.launch { securityStore.setAppLock(enabled) }
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
