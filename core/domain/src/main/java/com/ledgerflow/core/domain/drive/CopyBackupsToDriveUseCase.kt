package com.ledgerflow.core.domain.drive

import com.ledgerflow.core.common.time.Clock
import java.security.MessageDigest
import javax.inject.Inject

/** What one pass of copying to Drive came to. */
public sealed interface DriveOutcome {

    /** Everything is on Drive and verified. Counts are what this pass uploaded. */
    public data class Copied(val backups: Int, val attachments: Int, val bytes: Long, val removed: Int) : DriveOutcome

    /** Drive backup is off. Not recorded: nothing was tried. */
    public data object Off : DriveOutcome

    /** No reachable backup folder, or nothing in it yet: there is nothing to copy. */
    public data object NothingToCopy : DriveOutcome

    /** Consent is needed again; only the screen can ask. */
    public data object NeedsSignIn : DriveOutcome

    public data class Unavailable(val reason: DriveUnavailable) : DriveOutcome

    /** Drive could not be reached or refused a call. [retryable] asks the worker to try again. */
    public data class Failed(val retryable: Boolean, val reason: String) : DriveOutcome

    /** The short form kept in `app_meta`. Not `toString()`, which a refactor renames silently. */
    public fun record(): String = when (this) {
        is Copied -> "$RECORD_COPIED$backups,$attachments,$bytes"
        Off -> RECORD_OFF
        NothingToCopy -> RECORD_NOTHING
        NeedsSignIn -> RECORD_SIGN_IN
        is Unavailable -> RECORD_UNAVAILABLE + reason.name
        is Failed -> if (retryable) RECORD_RETRYING else RECORD_FAILED
    }

    public companion object {
        public const val RECORD_COPIED: String = "copied:"
        public const val RECORD_OFF: String = "off"
        public const val RECORD_NOTHING: String = "nothing"
        public const val RECORD_SIGN_IN: String = "sign-in"
        public const val RECORD_UNAVAILABLE: String = "unavailable:"
        public const val RECORD_RETRYING: String = "retrying"
        public const val RECORD_FAILED: String = "failed"

        /** Reads [record]'s form back; an unknown one (a later build's) reads as a failure. */
        public fun parse(record: String): DriveOutcome = when {
            record.startsWith(RECORD_COPIED) -> copied(record.removePrefix(RECORD_COPIED))
            record.startsWith(RECORD_UNAVAILABLE) -> Unavailable(
                DriveUnavailable.entries.firstOrNull { it.name == record.removePrefix(RECORD_UNAVAILABLE) }
                    ?: DriveUnavailable.Failed,
            )
            record == RECORD_OFF -> Off
            record == RECORD_NOTHING -> NothingToCopy
            record == RECORD_SIGN_IN -> NeedsSignIn
            record == RECORD_RETRYING -> Failed(retryable = true, reason = "")
            else -> Failed(retryable = false, reason = "")
        }

        private fun copied(counts: String): DriveOutcome {
            val parts = counts.split(',').map { it.toLongOrNull() ?: 0L }
            return Copied(
                backups = parts.getOrElse(0) { 0L }.toInt(),
                attachments = parts.getOrElse(1) { 0L }.toInt(),
                bytes = parts.getOrElse(2) { 0L },
                removed = 0,
            )
        }
    }
}

/** Where things go on Drive, and how many are kept (ADR-0034 §4, §5). */
public object DriveLayout {
    /** The app's folder in the user's Drive; the sidecars go in its [LocalBackupFiles.ATTACHMENTS]. */
    public const val FOLDER: String = "LedgerFlow backups"

    /** Owner's choice (d), the same as the phone keeps (§8 BUG4(d)). */
    public const val KEEP: Int = 5

    /** A backup this app named. Nothing else on Drive is ever deleted. */
    public val BACKUP_NAME: Regex = Regex("""ledgerflow-\d{8}-\d{6}\.lfbk""")

    /** A receipt sidecar this app named. */
    public val ATTACHMENT_NAME: Regex = Regex("""[^/]+\.lfba""")
}

/**
 * Copies the newest nightly backup and any receipt sidecars Drive lacks, verifies
 * each against Drive's MD5, then rotates (ADR-0034 §5). Runs in its own worker,
 * after the nightly pass, so nothing here can touch the local backup.
 *
 * **Bytes in, bytes out**: the files are read as the nightly pass sealed them
 * and uploaded unchanged. Nothing is decrypted, and nothing here holds a key.
 */
public class CopyBackupsToDriveUseCase @Inject constructor(
    private val status: DriveStatusStore,
    private val access: DriveAccess,
    private val drive: DriveFiles,
    private val local: LocalBackupFiles,
    private val clock: Clock,
) {

    public suspend operator fun invoke(): DriveOutcome {
        if (!status.enabled()) return DriveOutcome.Off
        var account: String? = null
        val outcome = when (val token = access.token()) {
            is DriveToken.Granted -> {
                account = token.account
                copyWith(token)
            }
            DriveToken.NeedsSignIn -> DriveOutcome.NeedsSignIn
            is DriveToken.Unavailable -> DriveOutcome.Unavailable(token.reason)
        }
        status.record(DriveAttempt(clock.nowMillis(), outcome, account))
        return outcome
    }

    /**
     * One pass, and one more with a fresh token if Drive refused this one: Play
     * services may hand back a token Drive has already expired. A second refusal
     * means the grant itself is gone.
     */
    private suspend fun copyWith(token: DriveToken.Granted): DriveOutcome {
        val files = local.snapshot()?.takeIf { it.backups.isNotEmpty() } ?: return DriveOutcome.NothingToCopy
        Pass(token.accessToken).run(files)?.let { return it }
        access.forget(token.accessToken)
        val again = access.token() as? DriveToken.Granted ?: return DriveOutcome.NeedsSignIn
        return Pass(again.accessToken).run(files) ?: DriveOutcome.NeedsSignIn
    }

    /**
     * The steps of one pass, each answering whether to go on. The first that
     * stops leaves its reason in [stopped], or sets [refused] for a 401.
     */
    private inner class Pass(private val token: String) {
        private var stopped: DriveOutcome? = null
        private var refused = false
        private var root = ""
        private var receipts = ""
        private var backups = 0
        private var attachments = 0
        private var bytes = 0L
        private var removed = 0

        /** The outcome, or null when Drive refused the token (HTTP 401): [copyWith] decides what that means. */
        suspend fun run(files: LocalBackupSet): DriveOutcome? {
            val done = folders() &&
                uploadMissing(root, files.backups.take(1).map(LocalBackupFile::Backup)) &&
                uploadMissing(receipts, files.attachments.map(LocalBackupFile::Attachment)) &&
                rotate(files)
            return when {
                done -> DriveOutcome.Copied(backups, attachments, bytes, removed)
                refused -> null
                else -> checkNotNull(stopped) { "a step stopped without a reason" }
            }
        }

        private suspend fun folders(): Boolean {
            root = drive.folder(token, DriveLayout.FOLDER, null).valueOrStop() ?: return false
            receipts = drive.folder(token, LocalBackupFiles.ATTACHMENTS, root).valueOrStop() ?: return false
            return true
        }

        /** Uploads each of [wanted] that [folderId] lacks, verifying each against Drive's MD5. */
        private suspend fun uploadMissing(folderId: String, wanted: List<LocalBackupFile>): Boolean {
            val there = drive.list(token, folderId).valueOrStop()?.map { it.name }?.toSet() ?: return false
            return wanted.filterNot { it.name in there }.all { file -> uploadOne(folderId, file) }
        }

        private suspend fun uploadOne(folderId: String, file: LocalBackupFile): Boolean {
            // Rotated away since the snapshot: nothing to copy, and nothing wrong.
            val content = local.read(file) ?: return true
            val sent = drive.upload(token, folderId, file.name, content).valueOrStop() ?: return false
            if (sent.md5?.lowercase() != md5Of(content)) {
                // What landed is not what was sealed: it must not stand as a backup.
                drive.delete(token, sent.id)
                stopped = DriveOutcome.Failed(retryable = true, reason = "Drive's copy of ${file.name} did not match")
                return false
            }
            when (file) {
                is LocalBackupFile.Backup -> backups++
                is LocalBackupFile.Attachment -> attachments++
            }
            bytes += content.size
            return true
        }

        /** Newest [DriveLayout.KEEP] backups, and sidecars the phone still has. Only names this app gives. */
        private suspend fun rotate(files: LocalBackupSet): Boolean {
            val remoteBackups = drive.list(token, root).valueOrStop() ?: return false
            val remoteReceipts = drive.list(token, receipts).valueOrStop() ?: return false
            val keepReceipts = files.attachments.toSet()
            val doomed = remoteBackups.filter { DriveLayout.BACKUP_NAME.matches(it.name) }
                .sortedByDescending { it.name }
                .drop(DriveLayout.KEEP) +
                remoteReceipts.filter { DriveLayout.ATTACHMENT_NAME.matches(it.name) && it.name !in keepReceipts }
            removed = doomed.count { drive.delete(token, it.id) is DriveCall.Ok }
            return true
        }

        private fun <T> DriveCall<T>.valueOrStop(): T? = when (this) {
            is DriveCall.Ok -> value
            DriveCall.Unauthorized -> null.also { refused = true }
            is DriveCall.Failed -> null.also { stopped = DriveOutcome.Failed(retryable, reason) }
        }
    }

    private fun md5Of(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
