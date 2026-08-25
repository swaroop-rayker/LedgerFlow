package com.ledgerflow.feature.ingest.adapters

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * The notification capture component, in **both** flavours (SPEC.md §5.2).
 *
 * ## What it deliberately does not do yet
 *
 * `onNotificationPosted` is **not overridden**, and that is the point rather
 * than an omission. CLAUDE.md §7 states the privacy guarantee as a hard rule:
 *
 * > The notification package allowlist filter runs **before** any notification
 * > body is read. Never log or persist content from a non-allowlisted package —
 * > this is a stated privacy guarantee, not an implementation detail.
 *
 * The allowlist is P2. Until it exists there is no version of reading
 * `sbn.notification.extras` that honours that sentence, so this service reads
 * nothing at all. A listener that is bound and silent is the only shape S11 can
 * ship honestly; the alternative — capture now, filter later — is the guarantee
 * broken in the one release where nobody would notice.
 *
 * P2 adds the override, and its first statement is the package check, before
 * any access to the notification's contents. Then: flatten title + text +
 * bigText + subText, build a
 * [com.ledgerflow.core.domain.ingest.RawIngestEvent], hand it to
 * [com.ledgerflow.feature.ingest.pipeline.IngestEventSink]. Nothing else.
 *
 * ## Why it is declared now
 *
 * Registering the component is what proves the flavour skeleton: the manifest
 * entry, the system binding, and the rebind handling below are all things that
 * fail on a real device rather than in a unit test, and finding that out at P2
 * would mean debugging capture and lifecycle at the same time. The listed
 * consequence — the app appears in Settings → Notification access — is accepted
 * knowingly: a user who grants it early gets a service that captures nothing,
 * which is exactly what it says on the tin until P2.
 */
public class NotificationIngestService : NotificationListenerService() {

    /**
     * OEM battery managers kill this service aggressively, and the system does
     * not re-bind on its own (§5.2).
     *
     * Cheap enough to belong in the skeleton, and it is the difference between a
     * listener that survives a week on the user's phone and one that quietly
     * dies on day two. §5.2's "> 6h dead" dashboard health banner is the other
     * half of this and lands with the Dashboard work.
     */
    override fun onListenerDisconnected() {
        Log.d(TAG, "Listener disconnected; requesting rebind.")
        requestRebind(ComponentName(this, javaClass))
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Listener connected.")
    }

    private companion object {
        private const val TAG = "NotificationIngest"
    }
}
