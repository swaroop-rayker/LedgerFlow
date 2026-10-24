package com.ledgerflow.core.domain.ingest

import kotlinx.coroutines.flow.Flow

/**
 * One app whose notifications LedgerFlow may read (SPEC §5.2, D-10; the
 * editor is `docs/V2-PLAN.md` §3.4, owner 2026-10-24).
 *
 * [builtIn] is the curated list shipped with the app: it can be switched off
 * and on again, never deleted, so a user who turns one off can find it and
 * turn it back on. An app the user added can be removed outright.
 */
public data class AllowlistedApp(
    val packageName: String,
    /** The app's name as Android shows it (D-11); null for a built-in not installed. */
    val label: String?,
    val enabled: Boolean,
    val builtIn: Boolean,
)

/**
 * The package allowlist, as the Settings editor sees it.
 *
 * Its own port rather than more methods on [RawIngestRepository]: capture
 * reads the list on a path with seconds to live (`isPackageAllowed`, before
 * any body is touched), and nothing here should sit on that path. The table is
 * the same one; the gate's behaviour follows from `enabled` and needs no
 * change.
 */
public interface PackageAllowlistRepository {

    /** Every app on the list, built-ins first then by name. */
    public fun observe(): Flow<List<AllowlistedApp>>

    public suspend fun setEnabled(packageName: String, enabled: Boolean)

    /** Adds [packageName], enabled. No effect if it is already on the list. */
    public suspend fun add(packageName: String, label: String)

    /** Removes an app the user added. Refuses a built-in: returns false. */
    public suspend fun remove(packageName: String): Boolean
}

/** An app installed on the phone that could be added to the list. */
public data class InstalledApp(
    val packageName: String,
    val label: String,
    /**
     * It handles SMS, or is a known chat app. Adding one means every personal
     * message it shows is read, kept and queued in the Inbox (SPEC §3.1), so
     * the editor warns first.
     */
    val carriesPersonalMessages: Boolean,
)

/** The apps the user can see in their launcher. `PackageManager`-backed. */
public interface InstalledApps {
    public suspend fun launchable(): List<InstalledApp>
}
