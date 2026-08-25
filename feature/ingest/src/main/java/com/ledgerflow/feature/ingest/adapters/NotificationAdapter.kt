package com.ledgerflow.feature.ingest.adapters

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The notification source, in **both** flavours (SPEC.md §3.1, D-04).
 *
 * Not the fallback for the Play build — the higher-recall source. UPI apps and
 * most banks post notifications for payments that never generate an SMS at all,
 * which is why §3.1 promotes this to co-equal rather than "later".
 *
 * The capture itself is [NotificationIngestService]; this object is only the
 * source-agnostic face of it, so a screen can ask whether it is working without
 * knowing what a `NotificationListenerService` is.
 */
@Singleton
public class NotificationAdapter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransactionIngestSource {

    override val sourceType: IngestSourceType = IngestSourceType.NOTIFICATION

    /**
     * Read fresh every time, off the main thread.
     *
     * `getEnabledListenerPackages` reads `Settings.Secure` through a binder
     * call, and the answer genuinely changes behind the app's back: the grant
     * lives in system Settings, not in a runtime prompt this process controls.
     */
    override suspend fun status(): IngestSourceStatus = withContext(ioDispatcher) {
        statusFor(
            listenerEnabled = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
        )
    }

    internal companion object {

        /**
         * The decision, separated from the platform lookup so it is testable
         * off-device.
         *
         * There is no `UNAVAILABLE_ON_DEVICE` case: every Android device that
         * can run this app can host a notification listener, and there is no
         * `UNSUPPORTED_IN_BUILD` case either — that is the entire difference
         * between this source and the SMS one.
         */
        internal fun statusFor(listenerEnabled: Boolean): IngestSourceStatus =
            if (listenerEnabled) IngestSourceStatus.READY else IngestSourceStatus.PERMISSION_REQUIRED
    }
}
