package com.ledgerflow.core.domain.security

/**
 * Settings > Security & privacy > Data permissions (V2-PLAN §3.5): every
 * permission this install holds, what it is for, and whether the user can
 * take it back.
 *
 * **Read from the installed package, not written down here.** The page lists
 * what Android says the app requested, so a flavour without SMS has no SMS
 * row, and a dependency that merges a new permission shows up on the page the
 * day it ships. [PermissionCatalogue] only says which row each one belongs
 * to; a permission it does not know is shown under its raw name rather than
 * left off (`DataPermissionsCoverTheManifestTest` keeps that list empty).
 */
public fun interface AppPermissions {
    /** The rows, in [PermissionKind]'s order. Read again on every resume: the user changes these elsewhere. */
    public fun held(): List<HeldPermission>
}

/** One row of the page. [androidNames] are the manifest names behind it. */
public data class HeldPermission(
    val kind: PermissionKind,
    val grant: PermissionGrant,
    val androidNames: List<String>,
)

/**
 * The rows, in the page's order: the four the user can turn off first, then
 * the ones Android grants at install and offers no switch for.
 */
public enum class PermissionKind(public val revocable: Boolean) {
    /** Notification access: a special access, granted in Android's settings, held by both flavours. */
    NOTIFICATION_ACCESS(revocable = true),
    RECEIVE_SMS(revocable = true),
    CAMERA(revocable = true),
    POST_NOTIFICATIONS(revocable = true),
    INTERNET(revocable = false),
    BIOMETRIC(revocable = false),

    /** WorkManager's four: wake lock, boot, network state, foreground service. */
    BACKGROUND_WORK(revocable = false),

    /** A permission [PermissionCatalogue] has no row for. Never expected; shown rather than hidden. */
    UNEXPLAINED(revocable = false),
}

public enum class PermissionGrant {
    ALLOWED,
    NOT_ALLOWED,

    /** A normal permission: granted when the app was installed, and not something Android lets a user switch off. */
    AT_INSTALL,
}

/** Which row each manifest permission belongs to. */
public object PermissionCatalogue {

    private val KINDS: Map<String, PermissionKind> = mapOf(
        "android.permission.RECEIVE_SMS" to PermissionKind.RECEIVE_SMS,
        "android.permission.CAMERA" to PermissionKind.CAMERA,
        "android.permission.POST_NOTIFICATIONS" to PermissionKind.POST_NOTIFICATIONS,
        "android.permission.INTERNET" to PermissionKind.INTERNET,
        "android.permission.USE_BIOMETRIC" to PermissionKind.BIOMETRIC,
        "android.permission.WAKE_LOCK" to PermissionKind.BACKGROUND_WORK,
        "android.permission.RECEIVE_BOOT_COMPLETED" to PermissionKind.BACKGROUND_WORK,
        "android.permission.ACCESS_NETWORK_STATE" to PermissionKind.BACKGROUND_WORK,
        "android.permission.FOREGROUND_SERVICE" to PermissionKind.BACKGROUND_WORK,
    )

    /** The row for [androidName], or [PermissionKind.UNEXPLAINED]. */
    public fun kindOf(androidName: String): PermissionKind = KINDS[androidName] ?: PermissionKind.UNEXPLAINED

    /**
     * A permission the app defines for itself and requests from itself
     * (`<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX
     * adds so its own receivers are not reachable from other apps). It guards
     * the app; it reaches nothing of the user's, so it has no row.
     */
    public fun isOwn(androidName: String, packageName: String): Boolean = androidName.startsWith("$packageName.")

    /**
     * The page's rows from what the package requested.
     *
     * @param granted whether Android reports a runtime permission granted.
     * @param notificationsOn whether the app may post notifications at all (the
     *   permission on Android 13+, and the app-wide switch on every version).
     * @param listenerOn whether the notification listener has access.
     */
    public fun rows(
        requested: List<String>,
        packageName: String,
        granted: (String) -> Boolean,
        notificationsOn: Boolean,
        listenerOn: Boolean,
    ): List<HeldPermission> {
        val byKind = requested
            .filterNot { isOwn(it, packageName) }
            .distinct()
            .groupBy { kindOf(it) }
        val listener = HeldPermission(PermissionKind.NOTIFICATION_ACCESS, grantOf(listenerOn), emptyList())
        val declared = byKind.flatMap { (kind, names) ->
            when (kind) {
                PermissionKind.UNEXPLAINED -> names.map { HeldPermission(kind, PermissionGrant.AT_INSTALL, listOf(it)) }
                PermissionKind.POST_NOTIFICATIONS -> listOf(HeldPermission(kind, grantOf(notificationsOn), names))
                PermissionKind.RECEIVE_SMS,
                PermissionKind.CAMERA,
                -> listOf(HeldPermission(kind, grantOf(names.all(granted)), names))
                PermissionKind.INTERNET,
                PermissionKind.BIOMETRIC,
                PermissionKind.BACKGROUND_WORK,
                PermissionKind.NOTIFICATION_ACCESS,
                -> listOf(HeldPermission(kind, PermissionGrant.AT_INSTALL, names))
            }
        }
        return (listOf(listener) + declared).sortedBy { it.kind.ordinal }
    }

    private fun grantOf(on: Boolean) = if (on) PermissionGrant.ALLOWED else PermissionGrant.NOT_ALLOWED
}
