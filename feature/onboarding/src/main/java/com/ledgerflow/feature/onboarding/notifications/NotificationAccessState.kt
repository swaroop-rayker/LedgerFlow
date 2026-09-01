package com.ledgerflow.feature.onboarding.notifications

import androidx.compose.runtime.Immutable

/**
 * §5.2's privacy hard rule, as the user reads it.
 *
 * > **Privacy hard rule:** LedgerFlow reads notifications **only** from the
 * > package allowlist. Notification content from non-allowlisted packages is
 * > never read, logged, or persisted — the filter runs before any body access.
 * > **This is stated verbatim in the permission explainer and in Settings.**
 *
 * That last sentence is the instruction rather than the rule, so it is the one
 * thing not reproduced here. Everything before it is byte-for-byte the spec's
 * own wording, and `PrivacyRuleIsVerbatimTest` reads `SPEC.md` at test time and
 * fails if the two ever drift.
 *
 * **The guard runs in both directions on purpose.** A promise the user is shown
 * is worth nothing if the sentence quietly softens over a refactor — but the
 * more likely drift is the other way: someone tightens the *implementation*,
 * updates the spec, and leaves the screen making the older, weaker promise. A
 * test that only checked "the screen says something about privacy" would pass
 * through both.
 */
public const val NOTIFICATION_PRIVACY_RULE: String =
    "LedgerFlow reads notifications only from the package allowlist. " +
        "Notification content from non-allowlisted packages is never read, " +
        "logged, or persisted — the filter runs before any body access."

/**
 * The explainer's state (SPEC.md §5.2).
 *
 * Two independent grants, and they are not interchangeable — which is the whole
 * reason this screen exists instead of a system dialog:
 *
 * - **Notification access** is what lets the app *read* payment notifications.
 *   It cannot be granted in-app under any circumstance; it lives in a system
 *   Settings page the app can only deep-link to (§5.2).
 * - **`POST_NOTIFICATIONS`** is what lets the app *show* the user an Inbox
 *   notification. It is an ordinary runtime prompt, and it is unrelated to
 *   capture: withholding it costs the announcement, never the candidate.
 *
 * Conflating them is the failure this screen is built to avoid. A user who
 * grants the runtime prompt and nothing else has a working Inbox that never
 * fills, and no way to tell from the app that anything is wrong.
 */
@Immutable
public data class NotificationAccessUiState(

    /** Notification access, read fresh on every resume (§5.2's poll). */
    val listenerGranted: Boolean = false,

    /**
     * `POST_NOTIFICATIONS`, or `true` below API 33 where it does not exist.
     *
     * Not "assume the best": below Tiramisu the permission is granted at
     * install and `checkSelfPermission` answers `DENIED` for a string the
     * permission manager has never heard of. `true` is the accurate answer, and
     * `AndroidInboxNotifier.canPost` already carries the same guard for the same
     * reason.
     */
    val postNotificationsGranted: Boolean = true,

    /** Whether to show the runtime-prompt row at all. False below API 33. */
    val postNotificationsApplicable: Boolean = false,

    /**
     * Whether a poll has completed.
     *
     * The screen renders nothing definite until it has, because the honest
     * initial value of [listenerGranted] is "not yet asked" and showing that as
     * "not granted" makes an already-configured install flash a red row on
     * every open.
     */
    val polled: Boolean = false,
)

/** Everything the screen can ask for, as one type (CLAUDE.md §5). */
public sealed interface NotificationAccessEvent {

    /**
     * Open the system notification-access page.
     *
     * Handled by the route rather than the ViewModel: it is an `Intent`, and a
     * ViewModel that starts activities is a ViewModel holding a `Context` for
     * the one reason it should not.
     */
    public data object OpenListenerSettings : NotificationAccessEvent

    /** Fire the `POST_NOTIFICATIONS` runtime prompt. Also route-handled. */
    public data object RequestPostNotifications : NotificationAccessEvent

    /**
     * The user is leaving the screen.
     *
     * Marks the explainer seen **however they leave it** — granted, declined or
     * simply read. Declining is a legitimate answer, and re-presenting this on
     * the next launch would turn an explanation into a nag. The Dashboard banner
     * and the Settings row are the standing routes back.
     */
    public data object Done : NotificationAccessEvent
}
