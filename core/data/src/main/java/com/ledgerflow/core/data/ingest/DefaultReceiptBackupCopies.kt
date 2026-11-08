package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.crypto.lfbk.LfbaContainer
import com.ledgerflow.core.data.backup.BackupFolderResolver
import com.ledgerflow.core.data.backup.DefaultBackupRepository
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.domain.receipts.ReceiptBackupCopies
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The backup folder's copy of a receipt, for "Remove from phone"'s check
 * (ADR-0035 §1, condition 2).
 *
 * A copy counts only if its header is sealed to **this install's** nightly key
 * (`LfbaContainer.sealedTo`, the test the nightly pass uses for "already
 * current"). A copy sealed to other words, or a v1 file not yet re-sealed, is
 * not one this install's restore could be sure of, so the receipt stays.
 * Header and bytes are read; nothing is decrypted, and nothing could be: the
 * phone holds no private key (ADR-0027).
 */
@Singleton
public class DefaultReceiptBackupCopies @Inject constructor(
    private val session: VaultSession,
    private val folders: BackupFolderResolver,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ReceiptBackupCopies {

    override suspend fun sealedCopy(id: String): ByteArray? = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext null
        runCatching {
            val meta = database.appMetaDao()
            val publicKey = meta.value(DefaultBackupRepository.KEY_SEAL_PUBLIC_KEY)?.fromHex()
                ?: return@runCatching null
            val folder = meta.value(VaultSession.KEY_BACKUP_TREE_URI)
                ?.let(folders::resolve)
                ?.takeIf { it.displayName() != null }
                ?: return@runCatching null
            val bytes = folder.subfolder(AttachmentBackup.IMAGES_DIRECTORY)
                ?.read("$id.${AttachmentBackup.EXTENSION}")
                ?: return@runCatching null
            bytes.takeIf { LfbaContainer.sealedTo(it, publicKey) }
        }.getOrNull()
    }

    private fun String.fromHex(): ByteArray? = runCatching {
        check(length % 2 == 0) { "odd-length hex" }
        chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    }.getOrNull()

    private companion object {
        const val HEX_RADIX = 16
    }
}
