package com.ledgerflow.navigation

/**
 * `ledgerflow://inbox/{pendingId}` -> a [Destination] (SPEC.md §5.1). P2-7.
 *
 * **Takes a `String`, not a `Uri`**, and that is what makes it testable. The
 * parsing rules here are the contract between a notification built in
 * `:feature:ingest` and a route declared in `:app` — two modules that cannot see
 * each other — and `android.net.Uri` is a stub that throws in a JVM unit test.
 * `MainActivity` hands over `intent.dataString` and this decides what it means.
 *
 * **It lives in `:app` beside [Destination] for the same reason the routes do**
 * (CLAUDE.md §3): a feature that owned this would have to own the destination
 * type too, and features never depend on features.
 *
 * Anything unrecognised is null rather than a fallback to the Dashboard. A deep
 * link the app does not understand should leave the user wherever they already
 * were, not silently move them.
 */
internal object InboxDeepLink {

    const val SCHEME: String = "ledgerflow"
    const val HOST: String = "inbox"

    /** What `:feature:ingest` builds its `PendingIntent` from. Kept in one place. */
    const val PREFIX: String = "$SCHEME://$HOST"

    /**
     * The destination a link names, or null if it names none.
     *
     * - `ledgerflow://inbox` -> the queue. This is the group summary's target:
     *   it stands for several candidates and cannot name one.
     * - `ledgerflow://inbox/{pendingId}` -> that candidate's review screen.
     *
     * A **stale id is not rejected here.** The row may have been purged, or the
     * link may be days old, and this layer has no database to ask. It routes,
     * and `ReviewViewModel` is where a candidate that no longer exists becomes
     * `InboxError.NotFound` on a screen that can say so — which is a better
     * place to find out than a notification tap that appears to do nothing.
     */
    fun parse(uri: String?): Destination? {
        val trimmed = uri?.trim().orEmpty()
        if (!trimmed.startsWith(PREFIX)) return null

        // Everything after the host, minus the separator and any query or
        // fragment someone appended. Deliberately not a split on "/": a
        // pendingId is a UUIDv7 and contains none, so a second segment means
        // this is not a link we wrote.
        val remainder = trimmed.removePrefix(PREFIX)
            .substringBefore('?')
            .substringBefore('#')

        return when {
            remainder.isEmpty() || remainder == "/" -> Destination.Inbox
            !remainder.startsWith('/') -> null
            else -> remainder.drop(1)
                .takeIf { it.isNotEmpty() && '/' !in it }
                ?.let(Destination::InboxReview)
        }
    }
}
