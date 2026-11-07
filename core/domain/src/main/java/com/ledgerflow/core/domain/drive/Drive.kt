package com.ledgerflow.core.domain.drive

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Google Drive keeps a copy of the nightly backup (ADR-0034). These are the
 * ports: [DriveAccess] and [DriveFiles] are implemented in `:core:drive`, the
 * only module that touches a network; [LocalBackupFiles] and [DriveStatusStore]
 * in `:core:data`. `:core:drive` is handed bytes and names and never sees the
 * vault, the database or a key.
 */
public interface DriveAccess {

    /**
     * A Drive access token for the `drive.file` scope, asked of Play services
     * afresh each time: LedgerFlow stores none. Never shows UI, so it can run in
     * a worker; when consent is needed it answers [DriveToken.NeedsSignIn].
     */
    public suspend fun token(): DriveToken

    /** Tells Play services a token was refused, so the next [token] is a new one. */
    public suspend fun forget(token: String)
}

public sealed interface DriveToken {
    public data class Granted(val accessToken: String, val account: String?) : DriveToken

    /** Consent is needed again (revoked, account removed): only the screen can ask. */
    public data object NeedsSignIn : DriveToken

    /** Play services is missing, or Google sign-in is not set up for this build. */
    public data class Unavailable(val reason: DriveUnavailable) : DriveToken
}

public enum class DriveUnavailable {
    /** No Google Play services on this phone. */
    NoPlayServices,

    /** Google refused this build's package and signing key: no OAuth client for it. */
    NotConfigured,

    /** Play services could not answer: no network for the token, or it failed. */
    Failed,
}

/** A file in Drive, as `drive.file` lets the app see it: only the ones it made. */
public data class RemoteFile(val id: String, val name: String, val md5: String?, val size: Long?)

/** What one call to Drive came to. */
public sealed interface DriveCall<out T> {
    public data class Ok<T>(val value: T) : DriveCall<T>

    /** The token was refused (HTTP 401). */
    public data object Unauthorized : DriveCall<Nothing>

    /** Anything else. [retryable] for a network error, a 429 or a 5xx. */
    public data class Failed(val retryable: Boolean, val reason: String) : DriveCall<Nothing>
}

/** Drive's REST API, the four calls ADR-0034 §4 names. */
public interface DriveFiles {

    /** The folder [name] under [parentId] (the Drive root when null), made if missing. */
    public suspend fun folder(token: String, name: String, parentId: String?): DriveCall<String>

    /** Every file the app made in [folderId]. */
    public suspend fun list(token: String, folderId: String): DriveCall<List<RemoteFile>>

    /** Uploads [bytes] as [name] in [folderId]; the answer carries Drive's MD5. */
    public suspend fun upload(token: String, folderId: String, name: String, bytes: ByteArray): DriveCall<RemoteFile>

    public suspend fun delete(token: String, fileId: String): DriveCall<Unit>
}

/**
 * What the nightly pass left in the phone's backup folder, already sealed:
 * the `.lfbk` files and, in [ATTACHMENTS], the receipt sidecars.
 */
public interface LocalBackupFiles {

    /** Names in the folder, or null when there is no reachable folder. */
    public suspend fun snapshot(): LocalBackupSet?

    /** One file's bytes, as the nightly pass wrote them; null if it has gone. */
    public suspend fun read(file: LocalBackupFile): ByteArray?

    public companion object {
        /** The sidecars' subfolder on the phone, mirrored on Drive (ADR-0023). */
        public const val ATTACHMENTS: String = "attachments"
    }
}

public data class LocalBackupSet(val backups: List<String>, val attachments: List<String>)

public sealed interface LocalBackupFile {
    public val name: String

    public data class Backup(override val name: String) : LocalBackupFile

    public data class Attachment(override val name: String) : LocalBackupFile
}

/**
 * Drive backup's switch and its last outcome, in the vault beside the nightly
 * backup's own (owner, 2026-11-07). It travels in a `.lfbk`, so a restore onto
 * a new phone remembers Drive was on and asks to sign in again.
 */
public interface DriveStatusStore {
    public fun observe(): Flow<DriveStatus>

    public suspend fun enabled(): Boolean

    public suspend fun setEnabled(enabled: Boolean)

    public suspend fun record(attempt: DriveAttempt)
}

/** What the Google Drive page shows. */
public data class DriveStatus(
    val enabled: Boolean,
    /** The last attempt, whatever it came to. */
    val lastAttempt: DriveAttempt?,
    /** When everything last arrived, verified. */
    val lastCopiedAt: Long?,
)

/** One upload pass, as recorded. [account] only when Play services said which. */
public data class DriveAttempt(val at: Long, val outcome: DriveOutcome, val account: String? = null)

/** The Google Drive page and the Data row's subtitle. */
public class ObserveDriveStatusUseCase @Inject constructor(private val store: DriveStatusStore) {
    public operator fun invoke(): Flow<DriveStatus> = store.observe()
}

/**
 * Turns Drive backup on or off. Turning off never touches the files on Drive:
 * they are the user's (ADR-0034 §6).
 */
public class SetDriveEnabledUseCase @Inject constructor(private val store: DriveStatusStore) {
    public suspend operator fun invoke(enabled: Boolean): Unit = store.setEnabled(enabled)
}
