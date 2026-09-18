package com.ledgerflow.core.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.PhraseVerification
import com.ledgerflow.core.crypto.UnlockFailure
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.backup.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Turns a stored tree URI into a folder, or null if its grant is gone. */
public fun interface BackupFolderResolver {
    public fun resolve(treeUri: String): BackupFolder?
}

/**
 * The app's resolver: the SAF tree, **only while its grant is still held**.
 *
 * A grant can be revoked from system settings, or lost when the provider is
 * uninstalled. Checking `persistedUriPermissions` first turns that into the
 * honest "choose a backup folder" rather than a write that fails midway.
 */
public class SafBackupFolderResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BackupFolderResolver {
    override fun resolve(treeUri: String): BackupFolder? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (held) SafBackupFolder.fromTree(context.contentResolver, uri) else null
    }

    /** The flags a grant must be persisted with — the picker's caller uses these. */
    public companion object {
        public const val GRANT_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/**
 * "Back up now" (SPEC.md §5.9, §16 Q23). The order of the steps is the design:
 *
 * 1. **The words open this vault**, checked read-only
 *    (`DekManager.verifyPhrase`) before anything is sealed. Every valid
 *    BIP-39 phrase passes the checksum; a backup under the wrong one would
 *    report success and never restore.
 * 2. **The folder is still granted.** Otherwise the user is asked to choose one,
 *    rather than finding out from a failure halfway through.
 * 3. **The `.lfbk` is written and verified** — the bytes that landed are
 *    decrypted and parsed before the file is put in place (§7).
 * 4. **Only then**: older backups rotate out, and `lastBackupAt` is recorded.
 *    A backup that failed verification changes neither, so the user's previous
 *    good backups are never deleted to make room for a bad one.
 * 5. **Images last** (ADR-0023), reported with their own counts: a verified
 *    ledger is a backup even if some images could not be copied, and the user
 *    is told how many.
 *
 * The seed exists for the length of one call and is zeroed in `finally`.
 */
@Singleton
public class DefaultBackupRepository @Inject constructor(
    private val session: VaultSession,
    private val dekManager: DekManager,
    private val attachments: AttachmentBackup,
    private val folders: BackupFolderResolver,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : BackupRepository {

    override suspend fun backUpNow(words: List<String>): BackupOutcome = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext BackupOutcome.VaultClosed

        when (val verification = dekManager.verifyPhrase(words)) {
            PhraseVerification.Opens -> Unit
            is PhraseVerification.Failure -> return@withContext verification.reason.toOutcome()
        }

        val folder = database.appMetaDao().value(VaultSession.KEY_BACKUP_TREE_URI)
            ?.let(folders::resolve)
            ?: return@withContext BackupOutcome.NoBackupFolder

        val seed = Bip39.toSeed(words)
        try {
            writeBackup(database, folder, seed)
        } finally {
            seed.fill(0)
        }
    }

    private suspend fun writeBackup(
        database: LedgerFlowDatabase,
        folder: BackupFolder,
        seed: ByteArray,
    ): BackupOutcome {
        val manager = DatabaseBackupManager(database)
        val sealed = manager.seal(seed)
        val now = clock.nowMillis()
        val name = fileNameFor(now)

        val written = folder.writeVerified(name, sealed.bytes) { landed ->
            manager.opensAs(landed, seed, sealed.rowCount)
        }
        if (!written) return BackupOutcome.WriteFailed

        val removed = rotate(folder, keep = KEEP, justWritten = name)
        database.appMetaDao().put(AppMetaEntity(AppMetaEntity.KEY_LAST_BACKUP_AT, now.toString()))

        val images = attachments.writeAll(folder, seed)
        return BackupOutcome.Done(
            fileName = name,
            rows = sealed.rowCount,
            imagesWritten = images.written,
            imagesAlreadyThere = images.alreadyCurrent,
            imagesUnreadable = images.unreadableLocally,
            imagesFailed = images.failed,
            olderBackupsRemoved = removed,
        )
    }

    /**
     * Keeps the newest [keep] backups (§8 BUG4(d): five), and **always** the
     * one just written, even if a clock set backwards makes it sort older.
     * Only files this app named are ever considered — nothing else in the
     * user's folder is touched.
     */
    private fun rotate(folder: BackupFolder, keep: Int, justWritten: String): Int {
        val older = folder.list()
            .filter { BackupNames.WRITTEN.matches(it) && it != justWritten }
            .sortedDescending()
            .drop(keep - 1)
        return older.count { folder.delete(it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun lastBackupAt(): Flow<Long?> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.appMetaDao()?.observeAll()?.map { rows ->
                rows.firstOrNull { it.key == AppMetaEntity.KEY_LAST_BACKUP_AT }?.value?.toLongOrNull()
            } ?: flowOf(null)
        }

    override suspend fun hasBackupFolder(): Boolean = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext false
        database.appMetaDao().value(VaultSession.KEY_BACKUP_TREE_URI)?.let(folders::resolve) != null
    }

    override suspend fun setBackupFolder(treeUri: String): Unit = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext
        database.appMetaDao().put(AppMetaEntity(VaultSession.KEY_BACKUP_TREE_URI, treeUri))
    }

    private fun UnlockFailure.toOutcome(): BackupOutcome = when (this) {
        UnlockFailure.AuthenticationFailed -> BackupOutcome.NotThisVaultsPhrase
        // The use case validated the checksum first, so this is unreachable
        // through it; if a caller skips that, the words still are not this
        // vault's usable phrase, and nothing is written.
        is UnlockFailure.InvalidMnemonic -> BackupOutcome.NotThisVaultsPhrase
        UnlockFailure.NotInitialized,
        UnlockFailure.KeystoreUnavailable,
        is UnlockFailure.MalformedBlob,
        is UnlockFailure.UnsupportedFormat,
        -> BackupOutcome.PhraseCheckUnavailable
    }

    private fun fileNameFor(millis: Long): String =
        "ledgerflow-" + SimpleDateFormat(STAMP, Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis)) + ".lfbk"

    private companion object {
        /** §8 BUG4(d). */
        const val KEEP = 5

        /**
         * UTC and fixed-width, so name order is time order — which is what
         * [rotate] relies on — whatever the device's time zone does.
         */
        const val STAMP = "yyyyMMdd-HHmmss"
    }
}
