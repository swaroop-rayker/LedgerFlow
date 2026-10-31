package com.ledgerflow.core.domain.security

import kotlinx.coroutines.flow.Flow

/**
 * Settings > Security & privacy (`docs/V2-PLAN.md` §3.5, SPEC §7.6; owner
 * 2026-10-24).
 *
 * **The app lock gates the screen, never the data.** It is an overlay the
 * phone's own fingerprint, face or PIN prompt lifts. It changes nothing about
 * the vault, the DEK or how the DEK is wrapped, and nothing here may ever ask
 * for `setUserAuthenticationRequired(true)` (CLAUDE.md §7): a biometric
 * re-enrolment must never be able to lock anyone out of their own ledger.
 */
public data class SecurityPreferences(
    val appLock: Boolean = false,
    val lockAfter: LockTimeout = LockTimeout.FIVE_MINUTES,
    val blockScreenshots: Boolean = false,
    /**
     * Home draws every amount as a mask until the user taps one (V2-PLAN
     * §3.5). A glance from beside the user, not a lock: the figures are one
     * tap away, and every other screen shows them as before.
     */
    val hideAmounts: Boolean = false,
)

/** How long the app may be away before it locks again (owner: 1 / 5 / 15 minutes, or at once). */
public enum class LockTimeout(public val millis: Long) {
    IMMEDIATELY(0L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(300_000L),
    FIFTEEN_MINUTES(900_000L),
}

/**
 * Whether the app should be locked now.
 *
 * - Off: never.
 * - On a cold start ([backgroundedAtMillis] null): always, since nothing has
 *   unlocked this process yet.
 * - Back from the background: once it has been away for [LockTimeout] or
 *   longer. At once means any time away.
 */
public fun shouldLock(prefs: SecurityPreferences, backgroundedAtMillis: Long?, nowMillis: Long): Boolean {
    if (!prefs.appLock) return false
    val away = backgroundedAtMillis ?: return true
    return nowMillis - away >= prefs.lockAfter.millis
}

/** Where [SecurityPreferences] are kept: `:core:datastore` (ADR-0030). */
public interface SecurityStore {
    public val preferences: Flow<SecurityPreferences>

    public suspend fun setAppLock(enabled: Boolean)

    public suspend fun setLockAfter(timeout: LockTimeout)

    public suspend fun setBlockScreenshots(enabled: Boolean)

    public suspend fun setHideAmounts(enabled: Boolean)
}

/**
 * Whether the phone has a screen lock (PIN, pattern, password) at all.
 *
 * The app lock cannot be turned on without one: the phone's prompt is what
 * lifts it, and a lock nothing can lift would strand the user.
 */
public fun interface DeviceSecurity {
    public fun hasScreenLock(): Boolean
}
