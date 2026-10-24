package com.ledgerflow.core.data.ingest

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.ingest.InstalledApp
import com.ledgerflow.core.domain.ingest.InstalledApps
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The apps in the launcher, for the allowlist editor's "Add an app" list
 * (V2-PLAN §3.4).
 *
 * **Launcher apps only, through a `<queries>` entry for that one intent**
 * (this module's manifest), never `QUERY_ALL_PACKAGES`: Play restricts that
 * permission, and an app the user cannot see in their launcher is not one
 * they would think to add. Names and package names only (D-10): nothing an
 * app has posted is read to build this list.
 *
 * **Which apps carry personal messages** is the phone's own answer where it has
 * one -- the apps that handle SMS, and the default SMS app -- plus a short list
 * of common chat and email apps that the phone has no intent for.
 */
internal class AndroidInstalledApps @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : InstalledApps {

    override suspend fun launchable(): List<InstalledApp> = withContext(io) {
        val pm = context.packageManager
        val smsApps = pm.activitiesFor(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
            .map { it.activityInfo.packageName }
            .toSet() + listOfNotNull(Telephony.Sms.getDefaultSmsPackage(context))
        pm.activitiesFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { (packageName, _) -> packageName }
            .filter { (packageName, _) -> packageName != context.packageName }
            .map { (packageName, label) ->
                InstalledApp(
                    packageName = packageName,
                    label = label,
                    carriesPersonalMessages = packageName in smsApps || packageName in PERSONAL_MESSAGE_APPS,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun PackageManager.activitiesFor(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }

    internal companion object {
        /**
         * Chat and email apps, which carry personal messages and have no intent
         * the phone answers for them. Not exhaustive; SMS handlers are found by
         * asking the phone.
         */
        internal val PERSONAL_MESSAGE_APPS: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.facebook.orca",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.discord",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.samsung.android.email.provider",
        )
    }
}

/** The editor's installed-app reader (V2-PLAN §3.4). */
@Module
@InstallIn(SingletonComponent::class)
internal interface InstalledAppsModule {

    @Binds
    fun installedApps(impl: AndroidInstalledApps): InstalledApps
}
