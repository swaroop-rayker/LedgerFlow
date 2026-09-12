package com.ledgerflow.core.common.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * May this app post a notification at all? **One definition, four callers.**
 *
 * There were two copies of this predicate and they had diverged, which is the
 * whole reason this file exists rather than a third copy:
 * `AndroidInboxNotifier.canPost` had the API-level guard below, worked out on
 * the device at P2-7, and `BudgetNotifications.canPost` did not. So §5.7's
 * threshold alerts could not post on any device below API 33 — silently, with
 * nothing anywhere reporting it. Two more sites (`:feature:ingest`'s group
 * summary and `InboxActionReceiver`'s failure notice) posted with no check at
 * all. It lives in `:core:common` beside [com.ledgerflow.core.common.time.OccurredAt]
 * and for the same stated reason: features never depend on features (CLAUDE.md
 * §3), and a rule two features have to agree on belongs under both of them.
 *
 * **The API-level guard is not defensive noise; without it this returns false
 * on every device below 33.** `POST_NOTIFICATIONS` became a runtime permission
 * in Tiramisu. On an older platform it is a string the permission manager has no
 * definition for, so `checkSelfPermission` answers `PERMISSION_DENIED` whatever
 * the manifest says — and `minSdk` is 26. That is a third of the supported range
 * on the paths whose entire job is to stop things happening silently.
 *
 * `areNotificationsEnabled` is the other half — the app or its channel muted in
 * system settings — which is not a permission at all and applies at every API
 * level.
 *
 * **What this is not: an exception guard.** The platform *drops* an ungranted
 * post rather than throwing, so there is no `SecurityException` to catch and a
 * `runCatching` around `notify` would report success. A notification that never
 * appears and never fails is indistinguishable from an event that never
 * happened, which is why the check is up front rather than a catch.
 *
 * **Lint still reports `MissingPermission` at every call site, and that is
 * structural.** `POST_NOTIFICATIONS` is declared once, in
 * `app/src/main/AndroidManifest.xml`, so that `:feature:ingest`'s manifest can
 * keep its absolute "no `uses-permission` element in this file" rule and
 * `restrictedPermissionCheck` can pin the whole set per source set (D-04). Lint
 * in a library module reads only that library's own manifest, so it cannot see
 * the declaration however thoroughly the grant is checked — hence a
 * `@Suppress("MissingPermission")` at each `notify`, each of which names this
 * object.
 *
 * No `androidx.core` here deliberately: at `minSdk` 26 `ContextCompat` and
 * `NotificationManagerCompat` both delegate straight to the platform calls
 * used below, and `:core:common` exports one dependency today.
 */
public object NotificationPostPolicy {

    /**
     * The decision, with no Android in it.
     *
     * Separated from [isPermitted] because the API-level half is the part that
     * was wrong and the part no test on this machine can reach: the device is
     * API 36, and Robolectric does not model a permission being *undefined* at
     * an API level — it would grant or deny whatever the test asked for and
     * assert nothing about the bug. A pure function over the three inputs is
     * testable, and `NotificationPostPolicyTest` covers the API-26 case that
     * silently answered `false` for two phases.
     *
     * The suppliers keep the short-circuit: below Tiramisu the permission is
     * never queried, and a muted app is never asked about a permission it does
     * not need.
     */
    internal fun mayPost(
        sdkInt: Int,
        postNotificationsGranted: () -> Boolean,
        notificationsEnabled: () -> Boolean,
    ): Boolean =
        (sdkInt < Build.VERSION_CODES.TIRAMISU || postNotificationsGranted()) &&
            notificationsEnabled()

    /** [mayPost], asked of this device. */
    public fun isPermitted(context: Context): Boolean = mayPost(
        sdkInt = Build.VERSION.SDK_INT,
        postNotificationsGranted = {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        notificationsEnabled = {
            context.getSystemService(NotificationManager::class.java)
                ?.areNotificationsEnabled() == true
        },
    )
}
