package com.ledgerflow.core.data.vault

import androidx.lifecycle.ProcessLifecycleOwner
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.UnlockFailure
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.crypto.lfbk.LfbkFailure
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.WalCheckpointObserver
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.backup.OpenedBackup
import com.ledgerflow.core.database.backup.RestoreResult
import com.ledgerflow.core.database.entity.AppMetaEntity
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
internal suspend fun registerWalCheckpoint(opened: LedgerFlowDatabase) {
    val observer = WalCheckpointObserver(opened)
    withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }
}

// ── Restore from a backup (§16 Q11, ADR-0026) ─────────────────────────────

/**
 * A wrap already on disk means an interrupted restore wrote it, and only its
 * words may finish the job; otherwise this is the first attempt and a new
 * DEK is made under these words.
 */
internal fun DekManager.dekForRestore(words: List<String>): UnlockResult =
    if (isInitialized()) unlockWithPhrase(words) else initialize(words)

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
        is LfbkFailure.UnsupportedFormat -> RestoreOutcome.NewerVersion(failure.version, LfbkContainer.FORMAT_VERSION)
        LfbkFailure.Corrupt,
        LfbkFailure.NotAnLfbkFile,
        is LfbkFailure.Malformed,
        -> RestoreOutcome.Damaged
    }
}

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
    }
    return (result as? RestoreResult.Success)?.rowCount
}

/** The marker, fsynced: its presence must survive the crash it exists for. */
internal fun writeRestoreMarker(marker: File, content: ByteArray): Boolean = runCatching {
    FileOutputStream(marker).use { stream ->
        stream.write(content)
        stream.flush()
        stream.fd.sync()
    }
}.isSuccess
