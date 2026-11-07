package com.ledgerflow.core.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.ledgerflow.core.domain.drive.DriveAccess
import com.ledgerflow.core.domain.drive.DriveToken
import com.ledgerflow.core.domain.drive.DriveUnavailable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A Drive access token from Play services' `AuthorizationClient`, scope
 * `drive.file` only (ADR-0034 §3). LedgerFlow stores no token: each call asks
 * again, and Play services answers silently while the grant stands.
 */
@Singleton
internal class PlayServicesDriveAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DriveAccess {

    override suspend fun token(account: String?): DriveToken {
        if (!hasPlayServices(context)) return DriveToken.Unavailable(DriveUnavailable.NoPlayServices)
        return when (val answer = authorize(context, account)) {
            is Authorization.Granted -> DriveToken.Granted(answer.accessToken)
            // A worker cannot show Google's consent sheet; the screen will.
            is Authorization.NeedsConsent -> DriveToken.NeedsSignIn
            is Authorization.Refused -> DriveToken.Unavailable(answer.reason)
        }
    }

    override suspend fun forget(token: String) {
        Identity.getAuthorizationClient(context)
            .clearToken(ClearTokenRequest.builder().setToken(token).build())
            .await()
    }
}

/**
 * Turning Drive backup on and off, from the screen (ADR-0034 §6): the same
 * request as [DriveAccess.token], but when Play services needs consent the
 * screen launches its intent and hands the result back through [complete].
 */
public interface DriveSignIn {
    /**
     * Asks for the grant for [account], the Google account the user just chose
     * in Android's account picker. Naming it is what lets turning off revoke
     * exactly that grant, and the nightly pass use exactly that account.
     */
    public suspend fun begin(account: String): DriveSignInStep

    /** The consent sheet's result. Null data, or a refusal, means the user said no. */
    public fun complete(data: Intent?): DriveSignInStep

    /**
     * Tells Google to drop this app's Drive grant for [account]. Best effort,
     * and nothing on Drive is deleted. Without the account, Play services
     * cannot be told which grant; the switch still goes off.
     */
    public suspend fun revoke(account: String?)
}

internal class PlayServicesDriveSignIn @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DriveSignIn {

    override suspend fun begin(account: String): DriveSignInStep {
        if (!hasPlayServices(context)) return DriveSignInStep.Unavailable(DriveUnavailable.NoPlayServices)
        return when (val answer = authorize(context, account)) {
            is Authorization.Granted -> DriveSignInStep.Granted
            is Authorization.NeedsConsent -> DriveSignInStep.NeedsConsent(answer.intent)
            is Authorization.Refused -> DriveSignInStep.Unavailable(answer.reason)
        }
    }

    override suspend fun revoke(account: String?) {
        account ?: return
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(account, GOOGLE_ACCOUNT_TYPE))
            .setScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        runCatching { Identity.getAuthorizationClient(context).revokeAccess(request).await() }
    }

    override fun complete(data: Intent?): DriveSignInStep {
        val result = runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        }.getOrElse { failure ->
            return DriveSignInStep.Unavailable(reasonOf(failure))
        }
        return if (result.accessToken != null) {
            DriveSignInStep.Granted
        } else {
            DriveSignInStep.Declined
        }
    }
}

/** Where turning Drive on stands. */
public sealed interface DriveSignInStep {
    public data object Granted : DriveSignInStep

    /** Launch [intent] (an `IntentSenderRequest`) and pass its result to [DriveSignIn.complete]. */
    public data class NeedsConsent(val intent: PendingIntent) : DriveSignInStep

    public data object Declined : DriveSignInStep

    public data class Unavailable(val reason: DriveUnavailable) : DriveSignInStep
}

private sealed interface Authorization {
    data class Granted(val accessToken: String) : Authorization

    data class NeedsConsent(val intent: PendingIntent) : Authorization

    data class Refused(val reason: DriveUnavailable) : Authorization
}

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/**
 * The `drive.file` grant, for [account] when it is known: Play services then
 * neither guesses among the phone's Google accounts nor asks which.
 */
private suspend fun authorize(context: Context, account: String?): Authorization {
    val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .apply { account?.let { setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) } }
        .build()
    val result = runCatching { Identity.getAuthorizationClient(context).authorize(request).await() }
        .getOrElse { failure -> return Authorization.Refused(reasonOf(failure)) }
    val token = result.accessToken
    val intent = result.pendingIntent
    return when {
        result.hasResolution() && intent != null -> Authorization.NeedsConsent(intent)
        token != null -> Authorization.Granted(token)
        else -> Authorization.Refused(DriveUnavailable.Failed)
    }
}

/**
 * `DEVELOPER_ERROR` is Google refusing this package and signing key: no OAuth
 * client registered for it (ADR-0034 §8). Everything else is a failure to try
 * again later.
 */
private fun reasonOf(failure: Throwable): DriveUnavailable =
    if ((failure as? ApiException)?.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
        DriveUnavailable.NotConfigured
    } else {
        DriveUnavailable.Failed
    }

private fun hasPlayServices(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

/** A Play services task, awaited without the `kotlinx-coroutines-play-services` dependency. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val failure = task.exception ?: if (task.isSuccessful) null else IllegalStateException("cancelled")
        continuation.resumeWith(if (failure == null) Result.success(task.result) else Result.failure(failure))
    }
}
