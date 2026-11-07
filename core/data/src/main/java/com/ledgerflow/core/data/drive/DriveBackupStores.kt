package com.ledgerflow.core.data.drive

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.backup.BackupFolderResolver
import com.ledgerflow.core.data.backup.BackupNames
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.drive.DriveAttempt
import com.ledgerflow.core.domain.drive.DriveLayout
import com.ledgerflow.core.domain.drive.DriveOutcome
import com.ledgerflow.core.domain.drive.DriveStatus
import com.ledgerflow.core.domain.drive.DriveStatusStore
import com.ledgerflow.core.domain.drive.LocalBackupFile
import com.ledgerflow.core.domain.drive.LocalBackupFiles
import com.ledgerflow.core.domain.drive.LocalBackupSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The phone's backup folder, as Drive copies it (ADR-0034): the `.lfbk` files
 * this app named, newest first, and the sealed sidecars beside them. Read as
 * the nightly pass wrote them; nothing here decrypts or writes.
 *
 * Opens the vault itself (CLAUDE.md §7, `:feature:ingest`): the upload worker
 * runs with no Activity alive, and the folder's grant lives in `app_meta`.
 */
@Singleton
public class DefaultLocalBackupFiles @Inject constructor(
    private val session: VaultSession,
    private val folders: BackupFolderResolver,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : LocalBackupFiles {

    override suspend fun snapshot(): LocalBackupSet? = withContext(io) {
        val folder = folder() ?: return@withContext null
        LocalBackupSet(
            backups = folder.list().filter { BackupNames.WRITTEN.matches(it) }.sortedDescending(),
            attachments = folder.subfolder(LocalBackupFiles.ATTACHMENTS)?.list().orEmpty()
                .filter { DriveLayout.ATTACHMENT_NAME.matches(it) }
                .sorted(),
        )
    }

    override suspend fun read(file: LocalBackupFile): ByteArray? = withContext(io) {
        val folder = folder() ?: return@withContext null
        when (file) {
            is LocalBackupFile.Backup -> folder.read(file.name)
            is LocalBackupFile.Attachment -> folder.subfolder(LocalBackupFiles.ATTACHMENTS)?.read(file.name)
        }
    }

    private suspend fun folder() = session.openForBackgroundWork()
        ?.appMetaDao()
        ?.value(VaultSession.KEY_BACKUP_TREE_URI)
        ?.let(folders::resolve)
}

/**
 * Drive backup's switch and its last outcome, in `app_meta` beside the nightly
 * backup's (owner, 2026-11-07, amending ADR-0034 §5): it travels in a `.lfbk`,
 * so a restored phone remembers Drive was on and asks to sign in again.
 */
@Singleton
public class DefaultDriveStatusStore @Inject constructor(
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DriveStatusStore {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<DriveStatus> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.appMetaDao()?.observeAll()?.map { rows ->
                statusOf(rows.associate { it.key to it.value })
            } ?: flowOf(DriveStatus(enabled = false, lastAttempt = null, lastCopiedAt = null))
        }

    override suspend fun enabled(): Boolean = withContext(io) {
        session.openForBackgroundWork()?.appMetaDao()?.value(KEY_ENABLED) == TRUE
    }

    override suspend fun account(): String? = withContext(io) {
        session.openForBackgroundWork()?.appMetaDao()?.value(KEY_ACCOUNT)
    }

    override suspend fun setEnabled(enabled: Boolean, account: String?): Unit = withContext(io) {
        val meta = session.openForBackgroundWork()?.appMetaDao() ?: return@withContext
        meta.put(AppMetaEntity(KEY_ENABLED, if (enabled) TRUE else FALSE))
        account?.let { meta.put(AppMetaEntity(KEY_ACCOUNT, it)) }
    }

    override suspend fun record(attempt: DriveAttempt): Unit = withContext(io) {
        val meta = session.openForBackgroundWork()?.appMetaDao() ?: return@withContext
        meta.put(AppMetaEntity(KEY_LAST_AT, attempt.at.toString()))
        meta.put(AppMetaEntity(KEY_LAST_OUTCOME, attempt.outcome.record()))
        if (attempt.outcome is DriveOutcome.Copied) meta.put(AppMetaEntity(KEY_LAST_COPIED_AT, attempt.at.toString()))
    }

    internal companion object {
        const val KEY_ENABLED = "driveEnabled"
        const val KEY_LAST_AT = "driveLastAttemptAt"
        const val KEY_LAST_OUTCOME = "driveLastOutcome"
        const val KEY_LAST_COPIED_AT = "driveLastCopiedAt"
        const val KEY_ACCOUNT = "driveAccount"
        private const val TRUE = "true"
        private const val FALSE = "false"

        fun statusOf(values: Map<String, String>): DriveStatus {
            val at = values[KEY_LAST_AT]?.toLongOrNull()
            val outcome = values[KEY_LAST_OUTCOME]?.let(DriveOutcome::parse)
            val attempt = if (at != null && outcome != null) DriveAttempt(at, outcome) else null
            return DriveStatus(
                enabled = values[KEY_ENABLED] == TRUE,
                lastAttempt = attempt,
                lastCopiedAt = values[KEY_LAST_COPIED_AT]?.toLongOrNull(),
                account = values[KEY_ACCOUNT],
            )
        }
    }
}
