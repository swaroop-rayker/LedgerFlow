package com.ledgerflow.core.data.backup

import android.content.Context
import android.net.Uri
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.data.ingest.AttachmentBackup
import com.ledgerflow.core.data.ingest.AttachmentRestoreReport
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.domain.backup.PutBackOutcome
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestorePreview
import com.ledgerflow.core.domain.backup.RestoreRepository
import com.ledgerflow.core.domain.backup.RestoreSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads one document the user picked. A seam so the restore is tested against
 * a file rather than the system picker, which is the only source of a real
 * document grant.
 */
public fun interface BackupDocumentReader {
    /** The whole document, or null if it cannot be read. */
    public fun read(documentUri: String): ByteArray?
}

/** The app's reader: `ContentResolver`, under the grant the picker gave. */
public class SafBackupDocumentReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BackupDocumentReader {
    override fun read(documentUri: String): ByteArray? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(documentUri))?.use { it.readBytes() }
    }.getOrNull()
}

/**
 * Restoring a backup: onto a new install (SPEC.md §7.3 step 3, §16 Q11), or
 * from Settings over the phone's vault (ADR-0033).
 *
 * The vault half — refusing when a vault exists, decrypting before writing,
 * the marker, the wrap under the backup's phrase, the one-transaction import —
 * is [VaultSession.restoreFromBackup]'s, because it is the vault's lifetime
 * being created. This class is what surrounds it: reading the bytes out of the
 * folder or file the user chose, and the receipt images afterwards
 * (ADR-0023), with their own counts.
 *
 * The seed exists for the length of one call and is zeroed in `finally`.
 */
@Singleton
public class DefaultRestoreRepository @Inject constructor(
    private val session: VaultSession,
    private val attachments: AttachmentBackup,
    private val folders: BackupFolderResolver,
    private val documents: BackupDocumentReader,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RestoreRepository {

    override suspend fun listBackups(treeUri: String): List<String>? = withContext(io) {
        folders.resolve(treeUri)
            ?.takeIf { it.displayName() != null }
            ?.let { folder -> BackupNames.newestFirst(folder.list()) }
    }

    override suspend fun restore(source: RestoreSource, words: List<String>): RestoreOutcome =
        restoreWith(source, words) { seed, bytes, treeUri ->
            session.restoreFromBackup(words, seed, bytes, treeUri)
        }

    override suspend fun replace(source: RestoreSource, words: List<String>): RestoreOutcome =
        restoreWith(source, words) { seed, bytes, treeUri ->
            session.replaceFromBackup(words, seed, bytes, treeUri)
        }

    /**
     * Either restore: read the backup, hand it to the session, and once its
     * rows are in, the images beside it — then the marker goes.
     */
    private suspend fun restoreWith(
        source: RestoreSource,
        words: List<String>,
        rowsFrom: suspend (seed: ByteArray, bytes: ByteArray, treeUri: String?) -> RestoreOutcome,
    ): RestoreOutcome = withContext(io) {
        val read = read(source) ?: return@withContext RestoreOutcome.SourceUnreadable
        val seed = Bip39.toSeed(words)
        try {
            when (val rows = rowsFrom(seed, read.bytes, read.treeUri)) {
                is RestoreOutcome.Done -> {
                    val images = read.folder?.let { attachments.restoreAll(it, seed) }
                        ?: attachments.reportWithoutFolder()
                    session.completeRestore()
                    rows.withImages(images)
                }
                else -> rows
            }
        } finally {
            seed.fill(0)
        }
    }

    override suspend fun preview(source: RestoreSource, words: List<String>): RestorePreview =
        withContext(io) {
            val read = read(source) ?: return@withContext RestorePreview.Refused(RestoreOutcome.SourceUnreadable)
            val seed = Bip39.toSeed(words)
            try {
                session.previewBackup(words, seed, read.bytes)
            } finally {
                seed.fill(0)
            }
        }

    override suspend fun canPutBack(): Boolean = session.canPutBack()

    override suspend fun putBack(): PutBackOutcome =
        if (session.putBackReplacedVault()) PutBackOutcome.Done else PutBackOutcome.Failed

    override suspend fun finish() {
        session.finishRestore()
    }

    /** The backup's bytes, and the folder they came from when there is one. */
    private class ReadBackup(val bytes: ByteArray, val folder: BackupFolder?, val treeUri: String?)

    private fun read(source: RestoreSource): ReadBackup? = when (source) {
        is RestoreSource.InFolder -> {
            val folder = folders.resolve(source.treeUri)
            folder?.read(source.fileName)?.let { ReadBackup(it, folder, source.treeUri) }
        }
        is RestoreSource.SingleFile -> documents.read(source.documentUri)?.let { ReadBackup(it, null, null) }
    }

    private fun RestoreOutcome.Done.withImages(images: AttachmentRestoreReport): RestoreOutcome.Done =
        copy(
            // Already present counts as restored: on a resumed restore it is
            // the image an earlier pass brought back.
            imagesRestored = images.restored + images.alreadyPresent,
            imagesNotFound = images.notFound,
            imagesUnreadable = images.unreadable,
            imagesFailed = images.failed,
        )
}

/** What a backup is called — shared by the writer, its rotation, and the restore's listing. */
internal object BackupNames {

    /** The name "Back up now" writes: UTC and fixed-width, so name order is time order. */
    val WRITTEN = Regex("""ledgerflow-\d{8}-\d{6}\.lfbk""")

    private const val EXTENSION = ".lfbk"

    /**
     * Every `.lfbk` in a folder, newest first. Any `.lfbk`, not only the app's
     * own names — a user who renamed one still has a backup — with the app's
     * timestamped names ahead of the rest, newest first.
     */
    fun newestFirst(names: List<String>): List<String> {
        val backups = names.filter { it.endsWith(EXTENSION, ignoreCase = true) }
        val (stamped, other) = backups.partition { WRITTEN.matches(it) }
        return stamped.sortedDescending() + other.sorted()
    }
}
