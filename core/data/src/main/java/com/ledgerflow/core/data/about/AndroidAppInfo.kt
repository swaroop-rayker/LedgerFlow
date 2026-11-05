package com.ledgerflow.core.data.about

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ledgerflow.core.domain.about.AppEdition
import com.ledgerflow.core.domain.about.AppInfo
import com.ledgerflow.core.domain.about.AppInfoReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject

/**
 * [AppInfo] from the installed package and `android.os.Build`.
 *
 * The edition is read from the package name rather than a build constant: the
 * Play app is `com.ledgerflow.playsafe[.debug]`, and the package is what the
 * user's phone actually has installed.
 */
internal class AndroidAppInfo @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppInfoReader {

    override fun read(): AppInfo {
        val info = packageInfo()
        return AppInfo(
            versionName = info?.versionName.orEmpty(),
            versionCode = info?.let(::versionCodeOf) ?: 0L,
            edition = if (PLAY_MARKER in context.packageName) AppEdition.PLAY else AppEdition.FULL,
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            device = listOf(Build.MANUFACTURER, Build.MODEL).filterNot { it.isNullOrBlank() }.joinToString(" "),
            languageTag = Locale.getDefault().toLanguageTag(),
        )
    }

    private fun packageInfo(): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private companion object {
        const val PLAY_MARKER = ".playsafe"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface AboutModule {
    @Binds
    fun appInfo(impl: AndroidAppInfo): AppInfoReader
}
