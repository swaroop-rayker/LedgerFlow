package com.ledgerflow.core.data.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.ledgerflow.core.domain.security.AppPermissions
import com.ledgerflow.core.domain.security.HeldPermission
import com.ledgerflow.core.domain.security.PermissionCatalogue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What Android says this install holds (Data permissions, V2-PLAN §3.5):
 * the package's requested permissions with their grant flags, the app-wide
 * notification switch, and whether the notification listener has access.
 *
 * Read from the package rather than a list kept here, so the page cannot
 * drift from the manifest ([PermissionCatalogue]).
 */
internal class AndroidAppPermissions @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppPermissions {

    override fun held(): List<HeldPermission> {
        val info = packageInfo()
        val names = info?.requestedPermissions?.toList().orEmpty()
        val flags = info?.requestedPermissionsFlags
        val granted = names.mapIndexed { index, name ->
            name to ((flags?.getOrNull(index) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
        }.toMap()
        return PermissionCatalogue.rows(
            requested = names,
            packageName = context.packageName,
            granted = { granted[it] == true },
            notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            listenerOn = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        )
    }

    private fun packageInfo(): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
    }.getOrNull()
}
