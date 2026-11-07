package com.ledgerflow.core.data.vault

import androidx.lifecycle.ProcessLifecycleOwner
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.UnlockFailure
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.crypto.kem.BackupSealKem
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.crypto.lfbk.LfbkFailure
import com.ledgerflow.core.data.backup.DefaultBackupRepository
import com.ledgerflow.core.data.drive.DefaultDriveStatusStore
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.WalCheckpointObserver
import com.ledgerflow.core.database.backup.BackupCounts
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.backup.OpenedBackup
import com.ledgerflow.core.database.backup.RestoreResult
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.backup.LedgerCounts
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultOutcome
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [VaultSession]'s stateless helpers: mappings between the crypto, database and
// domain vocabularies, and steps that touch nothing the session owns. Kept out
// of the class so what remains there is the lifetime it guards — the mutex, the
// handle and the key — and nothing that could be read without them.

// ── Unlock ────────────────────────────────────────────────────────────────

/**
 * Crypto vocabulary in, domain vocabulary out.
 *
 * Every branch lands somewhere recoverable -- that is the point of doing the
 * mapping explicitly rather than passing the crypto type upward.
 */
internal fun UnlockFailure.toRecoveryReason(): RecoveryReason = when (this) {
    UnlockFailure.KeystoreUnavailable -> RecoveryReason.KeystoreUnavailable
    UnlockFailure.NotInitialized -> RecoveryReason.KeystoreWrapMissing
    UnlockFailure.AuthenticationFailed -> RecoveryReason.KeystoreWrapDamaged
    is UnlockFailure.MalformedBlob -> RecoveryReason.KeystoreWrapDamaged
    is UnlockFailure.UnsupportedFormat -> RecoveryReason.KeystoreWrapDamaged
    is UnlockFailure.InvalidMnemonic -> RecoveryReason.KeystoreWrapDamaged
}

/** The same failures, seen from the phrase path, where they mean something else. */
internal fun UnlockFailure.toPhraseOutcome(): VaultOutcome = when (this) {
    // A well-formed phrase whose GCM tag did not verify: right format,
    // wrong vault. Not a typo -- validate() already ruled that out.
    UnlockFailure.AuthenticationFailed -> VaultOutcome.PhraseDidNotMatch
    is UnlockFailure.InvalidMnemonic -> VaultOutcome.PhraseRejected(PhraseValidation.ChecksumMismatch)
    UnlockFailure.NotInitialized -> VaultOutcome.Failed(RecoveryReason.KeystoreWrapMissing)
    else -> VaultOutcome.Failed(RecoveryReason.KeystoreWrapDamaged)
}

/**
 * BUG2's second countermeasure, finally attached to a real database.
 *
 * `ProcessLifecycleOwner` observers must be added on the main thread, and
 * this runs on IO -- hence the explicit hop rather than a bare `addObserver`.
 */
internal suspend fun registerWalCheckpoint(opened: LedgerFlowDatabase): WalCheckpointObserver {
    val observer = WalCheckpointObserver(opened)
    withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }
    return observer
}

/**
 * The other half, for a database the session closes. Left registered, the
 * observer's next `onStop` asks a closed Room for its writable database —
 * which reopens the file at its old path. Across a restore from Settings that
 * path is where the restored vault is being built (ADR-0033).
 */
internal suspend fun unregisterWalCheckpoint(observer: WalCheckpointObserver) {
    withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
    }
}

// ── Restore from a backup (§16 Q11, ADR-0026) ─────────────────────────────

/**
 * A wrap already on disk means an interrupted restore wrote it, and only its
 * words may finish the job; otherwise this is the first attempt and a new
 * DEK is made under these words.
 *
 * @param keepKeystoreKey a restore replacing a vault keeps the Keystore key
 *   the set-aside vault's wrap was sealed under (ADR-0033 f).
 */
internal fun DekManager.dekForRestore(words: List<String>, keepKeystoreKey: Boolean = false): UnlockResult =
    if (isInitialized()) {
        unlockWithPhrase(words, keepKeystoreKey)
    } else {
        initialize(words, keepKeystoreKey)
    }

/** Why a restore did not get its DEK. Only different words have their own sentence. */
internal fun UnlockFailure.toRestoreRefusal(): RestoreOutcome =
    if (this == UnlockFailure.AuthenticationFailed) {
        RestoreOutcome.NotTheInterruptedRestoresPhrase
    } else {
        RestoreOutcome.Failed
    }

/** Why a backup that did not open was refused. Never called with [OpenedBackup.Ready]. */
internal fun OpenedBackup.toRestoreRefusal(): RestoreOutcome = when (this) {
    is OpenedBackup.Ready -> RestoreOutcome.Failed
    is OpenedBackup.SchemaTooNew -> RestoreOutcome.NewerVersion(backupVersion, supported)
    is OpenedBackup.Failure -> when (val failure = reason) {
        LfbkFailure.WrongPhrase -> RestoreOutcome.WrongPhrase
        // The highest this build reads, which is the sealed format (ADR-0027),
        // not the oldest one it still opens.
        is LfbkFailure.UnsupportedFormat ->
            RestoreOutcome.NewerVersion(failure.version, LfbkContainer.FORMAT_VERSION_SEALED)
        LfbkFailure.Corrupt,
        LfbkFailure.NotAnLfbkFile,
        is LfbkFailure.Malformed,
        -> RestoreOutcome.Damaged
    }
}

/** Database vocabulary in, domain vocabulary out: the two books and the Inbox, never a total. */
internal fun BackupCounts.toLedgerCounts(): LedgerCounts =
    LedgerCounts(expenses = expenses, income = income, inbox = inbox)

/** The rows' half of the report; the caller adds the images'. */
internal fun restoredReport(rows: Int): RestoreOutcome.Done = RestoreOutcome.Done(
    rows = rows,
    imagesRestored = 0,
    imagesNotFound = 0,
    imagesUnreadable = 0,
    imagesFailed = 0,
)

/** The rows and the `app_meta` corrections, as one transaction. Null if it rolled back. */
internal suspend fun importRows(
    handle: LedgerFlowDatabase,
    opened: OpenedBackup.Ready,
    backupTreeUri: String?,
    backupTreeUriKey: String,
    carried: List<AppMetaEntity> = emptyList(),
): Int? {
    val result = DatabaseBackupManager(handle).restore(opened) {
        val meta = handle.appMetaDao()
        // The backup's, which may be older than this build.
        meta.put(AppMetaEntity(AppMetaEntity.KEY_SCHEMA_VERSION, LedgerFlowDatabase.VERSION.toString()))
        // The old phone's folder grant does not exist on this install.
        if (backupTreeUri != null) {
            meta.put(AppMetaEntity(backupTreeUriKey, backupTreeUri))
        } else {
            meta.remove(backupTreeUriKey)
        }
        // The backup just restored is a backup that exists (owner, 2026-11-07):
        // a backup is sealed before its own time is recorded, so it carries the
        // one before it, or none for a first backup.
        val recorded = meta.value(AppMetaEntity.KEY_LAST_BACKUP_AT)?.toLongOrNull() ?: 0L
        meta.put(AppMetaEntity(AppMetaEntity.KEY_LAST_BACKUP_AT, maxOf(recorded, opened.createdAt).toString()))
        // What the restore keeps or adds over the backup's own rows: the
        // nightly key, and on a replace this phone's Drive setting.
        carried.forEach { meta.put(it) }
    }
    return (result as? RestoreResult.Success)?.rowCount
}

/** This marker exists and says [content]: a restore from Settings rather than one at first run. */
internal fun File.holds(content: ByteArray): Boolean =
    runCatching { isFile && readBytes().contentEquals(content) }.getOrDefault(false)

/** The marker, fsynced: its presence must survive the crash it exists for. */
internal fun writeRestoreMarker(marker: File, content: ByteArray): Boolean = runCatching {
    FileOutputStream(marker).use { stream ->
        stream.write(content)
        stream.flush()
        stream.fd.sync()
    }
}.isSuccess

/**
 * The nightly backups' public key for [seed] (ADR-0027), as a row to carry into
 * a restored vault. The words were just proved to open this backup, which is
 * the same proof Back up now enrols on (owner, 2026-11-07); without it a
 * restored first backup has no key, and nightly backups stay off until the
 * user backs up again. Null if the key cannot be derived: the restore goes on.
 */
internal fun nightlyKeyRow(seed: ByteArray): AppMetaEntity? =
    runCatching { BackupSealKem.publicKey(seed) }.getOrNull()
        ?.let { key -> AppMetaEntity(DefaultBackupRepository.KEY_SEAL_PUBLIC_KEY, key.toHex()) }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Google Drive's setting on this phone, kept across a replace (owner, 2026-11-07). */
internal suspend fun driveRowsOf(current: LedgerFlowDatabase): List<AppMetaEntity> {
    val keys = setOf(
        DefaultDriveStatusStore.KEY_ENABLED,
        DefaultDriveStatusStore.KEY_ACCOUNT,
        DefaultDriveStatusStore.KEY_LAST_AT,
        DefaultDriveStatusStore.KEY_LAST_OUTCOME,
        DefaultDriveStatusStore.KEY_LAST_COPIED_AT,
    )
    return runCatching { current.appMetaDao().all().filter { it.key in keys } }.getOrDefault(emptyList())
}

