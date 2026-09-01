package com.ledgerflow.feature.onboarding.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.NotificationSetupStore
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The explainer's state (SPEC.md §5.2).
 *
 * **Polled, never observed, and that is §5.2's own instruction:** "polls
 * `NotificationManagerCompat.getEnabledListenerPackages()` on resume to
 * confirm". Neither grant notifies this process when it changes. Notification
 * access is granted on a system Settings page in another task — the user leaves,
 * toggles a switch, and comes back — and a resume is the only moment the app can
 * discover what happened. A `Flow` over that would be a `Flow` that is wrong for
 * as long as the user is away, which is the entire interesting interval.
 *
 * The listener half goes through [GetIngestSourceStatusUseCase] rather than
 * reading `getEnabledListenerPackages` here, so this screen learns the answer
 * from the same source the Dashboard banner and any future Settings row do. A
 * second reader of the same platform call is a second place for it to be read
 * subtly differently.
 */
@HiltViewModel
public class NotificationAccessViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val getIngestSourceStatus: GetIngestSourceStatusUseCase,
    private val setupStore: NotificationSetupStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        NotificationAccessUiState(
            postNotificationsApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        ),
    )
    public val state: StateFlow<NotificationAccessUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    public fun onEvent(event: NotificationAccessEvent) {
        when (event) {
            NotificationAccessEvent.Done -> markSeen()

            // Both are Intents, and the route owns them -- see the KDoc on
            // NotificationAccessEvent.OpenListenerSettings. They arrive here
            // only because one event type means one `when`, and an unhandled
            // branch on a sealed interface is a compile error rather than a
            // silently ignored tap.
            NotificationAccessEvent.OpenListenerSettings,
            NotificationAccessEvent.RequestPostNotifications,
            -> Unit
        }
    }

    /**
     * Re-read both grants.
     *
     * Called from `init` and from every resume. Cheap enough to run
     * unconditionally — two binder calls — and running it unconditionally is
     * what makes the confirmation §5.2 asks for actually happen rather than
     * happen when we remember.
     */
    public fun refresh() {
        viewModelScope.launch {
            val listenerReady = getIngestSourceStatus()[IngestSourceType.NOTIFICATION] ==
                IngestSourceStatus.READY
            val canPost = withContext(io) { postNotificationsGranted() }
            _state.update {
                it.copy(
                    listenerGranted = listenerReady,
                    postNotificationsGranted = canPost,
                    polled = true,
                )
            }
        }
    }

    private fun markSeen() {
        viewModelScope.launch { setupStore.markSetupSeen() }
    }

    /**
     * The runtime grant, with the API-level guard that is not defensive noise.
     *
     * `POST_NOTIFICATIONS` became a runtime permission at Tiramisu. On an older
     * platform it is a string the permission manager has never heard of, so
     * `checkSelfPermission` answers `DENIED` whatever the manifest says — and
     * `minSdk` here is 26. Without the guard this screen would show a permanent
     * "not allowed" row, with a button that opens a dialog that cannot appear,
     * across a third of the supported range. `AndroidInboxNotifier.canPost`
     * carries the same guard for the same reason.
     */
    private fun postNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
}
