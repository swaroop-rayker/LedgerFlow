package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AttachmentEntity
import com.ledgerflow.core.domain.ingest.AttachmentOutcome
import com.ledgerflow.core.domain.ingest.AttachmentRepository
import com.ledgerflow.core.domain.ingest.AttachmentUsage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Receipt images on disk (SPEC.md §5.3, §7.1; ADR-0023). Schema v11.
 *
 * ## Where, and under what
 *
 * `filesDir/attachments/`, AES-256-GCM. **Never `cacheDir`** — Law 5, and
 * `bannedApiCheck` enforces it; `cacheDir` is for decoded-image scratch and
 * the system may delete it at any moment, which for the only copy of a receipt
 * would be silent loss. Never external storage.
 *
 * The key is `AttachmentKey.local`, derived from the DEK rather than being it
 * (ADR-0023 as amended) — see `VaultSession.attachmentKeyOrNull`.
 *
 * ## The file layout, and the two things that are load-bearing
 *
 * **The stored path is relative.** `filesDir` differs across a reinstall and
 * across a restore onto another device, so an absolute path would resolve on
 * the machine that wrote it and nowhere else — and the symptom is a receipt
 * that vanished rather than an error. `AttachmentPathIsRelativeTest` asserts
 * it; this class is the only writer, so it is the only place it can go wrong.
 *
 * **`sha256` is over the plaintext.** Computed before sealing. It is what
 * makes storing the same receipt twice return the first one: a hash of
 * ciphertext under a fresh nonce is different every time and dedupes nothing.
 *
 * ## The nonce lives in the file, in front of the ciphertext
 *
 * 12 bytes of nonce then the GCM ciphertext-with-tag. No header beyond that,
 * and deliberately no AAD: §5.9 requires the `.lfbk` header to be AAD because
 * that header steers the *restore path* and an attacker who could edit it
 * could redirect what happens to the file. Here there is no header to steer
 * anything with — the row in the database says what the file is — so there is
 * nothing to authenticate that the tag does not already cover.
 *
 * ## Writes are atomic
 *
 * `.tmp` → write → fsync → rename, the discipline §7 already requires of the
 * backup writer. A process death mid-write otherwise leaves a truncated file
 * that authenticates as damaged, and the row would point at it. The rename is
 * the commit point, and the row is written only after it succeeds.
 *
 * The layout and that write live in [LocalAttachmentSeal], shared with
 * [AttachmentBackup] — which reads these files to seal a copy for the backup
 * folder and writes them when restoring one. Two descriptions of one byte
 * format is how a file written by one path stops opening on the other.
 *
 * Unlike the backup writer this does **not** decrypt-and-verify before the
 * rename. That rule exists because a `.lfbk` is the user's last copy and an
 * unverified one is not a backup; a receipt image has the ledger entry beside
 * it and a phrase-sealed copy in the backup tree, so reading every image back
 * through GCM on the capture path would buy little for a cost paid on every
 * scan.
 */
@Singleton
public class DefaultAttachmentRepository @Inject constructor(
    private val files: AttachmentFiles,
    private val session: VaultSession,
    private val clock: Clock,
    private val ids: Uuid7Generator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : AttachmentRepository {

    override suspend fun store(bytes: ByteArray, mime: String): AttachmentOutcome =
        withContext(io) {
            // Opened here rather than required, for §5.3's sake as much as
            // §5.1's: nothing guarantees an Activity is alive by the time a
            // capture finishes being processed.
            val database = session.openForBackgroundWork()
                ?: return@withContext AttachmentOutcome.VaultClosed
            val key = session.attachmentKeyOrNull()
                ?: return@withContext AttachmentOutcome.VaultClosed

            try {
                val digest = sha256(bytes)
                val dao = database.attachmentDao()

                // Content-addressed idempotency. A user who scans the same
                // receipt twice gets one image and one row, and the caller is
                // told which so it can say so rather than quietly producing a
                // second candidate.
                dao.bySha256(digest)?.let {
                    return@withContext AttachmentOutcome.AlreadyStored(it.id)
                }

                val id = ids.generate()
                val relativePath = "$id$EXTENSION"
                val sealed = LocalAttachmentSeal.seal(key, bytes)

                if (!LocalAttachmentSeal.writeAtomically(files.resolve(relativePath), sealed)) {
                    return@withContext AttachmentOutcome.WriteFailed
                }

                dao.insert(
                    AttachmentEntity(
                        id = id,
                        // Null until approval: the image exists before the
                        // entry does, which is why the FK is nullable.
                        entryId = null,
                        filePath = relativePath,
                        mime = mime,
                        sha256 = digest,
                        // Plaintext length. The file on disk is larger by the
                        // nonce and the tag.
                        bytes = bytes.size.toLong(),
                        createdAt = clock.nowMillis(),
                    ),
                )
                AttachmentOutcome.Stored(id)
            } finally {
                // The session handed out a copy; blank it rather than waiting
                // for GC.
                key.fill(0)
            }
        }

    override suspend fun read(attachmentId: String): ByteArray? = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext null
        val key = session.attachmentKeyOrNull() ?: return@withContext null

        try {
            val row = database.attachmentDao().byId(attachmentId) ?: return@withContext null
            val file = files.resolve(row.filePath)
            if (!file.isFile) {
                // ADR-0023's honest-degradation case: a restore from a `.lfbk`
                // moved without its sibling images leaves rows whose files are
                // absent. Null, not a crash and not an empty image.
                return@withContext null
            }

            LocalAttachmentSeal.open(key, file.readBytes())
        } catch (_: java.io.IOException) {
            null
        } finally {
            key.fill(0)
        }
    }

    override suspend fun usage(): AttachmentUsage = withContext(io) {
        val present = files.all()
        AttachmentUsage(count = present.size, bytes = present.sumOf { it.length() })
    }

    /**
     * Everything, rows first.
     *
     * **Rows before files**, which is the opposite order from the purge and
     * deliberately so. There the database is the record and a file outliving
     * its row by a crash is recoverable garbage; here the *user asked for the
     * images to be gone*, and a row outliving its file would leave every
     * surface drawing a receipt it cannot load. Interrupted halfway, this
     * leaves orphaned files that the next run of the same action removes —
     * `files.all()` enumerates the directory rather than the table.
     */
    override suspend fun deleteAll(): Int = withContext(io) {
        val database = session.openForBackgroundWork() ?: return@withContext 0
        runCatching {
            database.attachmentDao().all().forEach { database.attachmentDao().deleteById(it.id) }
            files.all().count { it.delete() }
        }.getOrDefault(0)
    }

    private fun sha256(bytes: ByteArray): String = LocalAttachmentSeal.sha256(bytes)

    private companion object {
        val EXTENSION = ".${AttachmentFiles.EXTENSION}"
    }
}
