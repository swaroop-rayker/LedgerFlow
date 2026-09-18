package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.crypto.lfbk.LfbaContainer
import com.ledgerflow.core.crypto.lfbk.LfbaResult
import com.ledgerflow.core.data.backup.BackupFolder
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AttachmentEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What one pass over the backup folder did (ADR-0023).
 *
 * Counts rather than a boolean, because the honest sentence a user is owed
 * names a number — "12 receipt images weren't found alongside this backup" —
 * and because the interesting outcomes are not failures: an image already
 * copied is the normal case on every pass after the first.
 */
public data class AttachmentBackupReport(
    /** Sealed into the folder by this pass. */
    val written: Int = 0,
    /** Already there, already openable with this phrase. Nothing to do. */
    val alreadyCurrent: Int = 0,
    /** The local file is missing, damaged, or is not what its row says it is. */
    val unreadableLocally: Int = 0,
    /** Sealed correctly and could not be written: no space, no permission. */
    val failed: Int = 0,
)

/** What one restore pass recovered, and what it could not. */
public data class AttachmentRestoreReport(
    val restored: Int = 0,
    /** The local image is already present and intact. */
    val alreadyPresent: Int = 0,
    /** **ADR-0023's honest-degradation count**: rows whose image is not in the folder. */
    val notFound: Int = 0,
    /** Present but wrong phrase, damaged, misnamed, or not the image the row describes. */
    val unreadable: Int = 0,
    /** Recovered and could not be written locally. */
    val failed: Int = 0,
)

/**
 * ADR-0023's second half: the receipt images that live **beside** the `.lfbk`.
 *
 * The `.lfbk` carries `attachment` *rows*; this carries the pictures. Without
 * it a phrase-only restore returns a ledger whose receipts are all missing, and
 * §13.1's "every row comes back" is true in letter and hollow in spirit.
 *
 * ## The two seals, and why the bytes are re-sealed rather than copied
 *
 * On the device an image is sealed under `AttachmentKey.local`, derived from
 * the **DEK**. In the folder it is sealed under `AttachmentBackupKey`, derived
 * from the **BIP-39 seed**. So a backup pass opens each local file and seals it
 * again for travel; a restore does the reverse. Copying the local file instead
 * would be faster and wrong: a leaked backup folder must be as useless as a
 * leaked `.lfbk`, and the DEK-sealed form is openable by anything that has the
 * device's Keystore. It is also what makes a restore onto a *new* install work
 * at all — the new vault has a different DEK, so a DEK-sealed file copied
 * across would never open again.
 *
 * ## Writes are incremental, judged by the phrase and not by the filesystem
 *
 * An attachment is written to the folder once and never rewritten (ADR-0023:
 * "a night with no new receipts writes nothing"). But "the file exists" is the
 * wrong test after a phrase rotation — every copy would then be sealed under
 * words the user no longer has, and skipping them leaves a folder that quietly
 * cannot be restored. So a copy counts as current only if its `keyCheck`
 * matches the current phrase, read from the header without decrypting anything.
 *
 * ## Every write is verified before it counts
 *
 * `.tmp` → fsync → **decrypt-and-parse the file that landed** → rename, which
 * is §7's rule for the backup writer and applies here for the same reason: this
 * copy may be the user's only one. That is the difference from the local store,
 * which deliberately skips verification because the entry and this copy are
 * both beside it.
 *
 * ## Its caller is "Back up now"
 *
 * `DefaultBackupRepository` runs this after the `.lfbk` is written and
 * verified (ADR-0025). There is no scheduled backup: writing one needs the
 * BIP-39 seed, and the app never holds the phrase after onboarding (ADR-0011),
 * so the user starts each backup and types the words for it.
 */
@Singleton
public class AttachmentBackup @Inject constructor(
    private val files: AttachmentFiles,
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Seals every attachment into `[backupFolder]/attachments/`.
     *
     * @param backupFolder the user's backup folder -- a SAF tree in the app, a
     *   directory in the tests ([BackupFolder]).
     * @param seed the BIP-39 seed. **Never a passphrase** (CLAUDE.md §0) — this
     *   folder travels with the `.lfbk`.
     */
    public suspend fun writeAll(backupFolder: BackupFolder, seed: ByteArray): AttachmentBackupReport =
        withContext(io) {
            val database = session.openForBackgroundWork()
                ?: return@withContext AttachmentBackupReport()
            val rows = database.attachmentDao().all()
            // A folder that cannot hold the images fails every one of them,
            // and says so, rather than reporting a backup with no pictures as
            // a clean pass.
            val directory = backupFolder.subfolder(IMAGES_DIRECTORY)
                ?: return@withContext AttachmentBackupReport(failed = rows.size)
            val key = session.attachmentKeyOrNull()
                ?: return@withContext AttachmentBackupReport()

            try {
                rows.fold(AttachmentBackupReport()) { report, row ->
                    writeOne(row, directory, key, seed, report)
                }
            } finally {
                key.fill(0)
            }
        }

    private fun writeOne(
        row: AttachmentEntity,
        directory: BackupFolder,
        key: ByteArray,
        seed: ByteArray,
        report: AttachmentBackupReport,
    ): AttachmentBackupReport {
        val target = fileNameFor(row.id)
        if (isCurrentCopy(directory, target, seed)) {
            return report.copy(alreadyCurrent = report.alreadyCurrent + 1)
        }

        // Read what is actually on this device, and check it is what the row
        // says. Sealing an image that does not match its `sha256` would copy a
        // corruption into the one place meant to survive it.
        val local = files.resolve(row.filePath)
        val plaintext = local.takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
            ?.let { LocalAttachmentSeal.open(key, it) }
            ?.takeIf { LocalAttachmentSeal.sha256(it) == row.sha256 }
            ?: return report.copy(unreadableLocally = report.unreadableLocally + 1)

        val sealed = LfbaContainer.write(plaintext, seed, row.id)
        return if (writeVerified(directory, target, sealed, seed, row.id, plaintext)) {
            report.copy(written = report.written + 1)
        } else {
            report.copy(failed = report.failed + 1)
        }
    }

    /**
     * Restores the images for every `attachment` row, reporting what is absent.
     *
     * Run **after** the `.lfbk` restore, which is what puts the rows there: the
     * rows say which images to look for and what each should hash to.
     */
    public suspend fun restoreAll(backupFolder: BackupFolder, seed: ByteArray): AttachmentRestoreReport =
        withContext(io) {
            val database = session.openForBackgroundWork()
                ?: return@withContext AttachmentRestoreReport()
            val key = session.attachmentKeyOrNull()
                ?: return@withContext AttachmentRestoreReport()
            // Created if absent; an empty folder then reports every row as
            // not found, which is the honest answer.
            val directory = backupFolder.subfolder(IMAGES_DIRECTORY)
                ?: return@withContext AttachmentRestoreReport()

            try {
                database.attachmentDao().all().fold(AttachmentRestoreReport()) { report, row ->
                    restoreOne(row, directory, key, seed, report)
                }
            } finally {
                key.fill(0)
            }
        }

    /**
     * What one row's restore did, decided separately from the counting.
     *
     * The decision is a `when` over the states a row can be in; folding the
     * report is arithmetic. Keeping them apart is also what keeps this
     * readable as the list of outcomes a user is owed.
     */
    private enum class RestoreOutcome { RESTORED, ALREADY_PRESENT, NOT_FOUND, UNREADABLE, FAILED }

    private fun restoreOne(
        row: AttachmentEntity,
        directory: BackupFolder,
        key: ByteArray,
        seed: ByteArray,
        report: AttachmentRestoreReport,
    ): AttachmentRestoreReport = when (outcomeFor(row, directory, key, seed)) {
        RestoreOutcome.RESTORED -> report.copy(restored = report.restored + 1)
        RestoreOutcome.ALREADY_PRESENT -> report.copy(alreadyPresent = report.alreadyPresent + 1)
        RestoreOutcome.NOT_FOUND -> report.copy(notFound = report.notFound + 1)
        RestoreOutcome.UNREADABLE -> report.copy(unreadable = report.unreadable + 1)
        RestoreOutcome.FAILED -> report.copy(failed = report.failed + 1)
    }

    private fun outcomeFor(
        row: AttachmentEntity,
        directory: BackupFolder,
        key: ByteArray,
        seed: ByteArray,
    ): RestoreOutcome {
        val local = files.resolve(row.filePath)
        val source = fileNameFor(row.id)

        return when {
            // Never overwrite an image that is already here and intact: the
            // restore's job is to fill gaps, not to replace good files.
            isIntactLocally(local, key, row.sha256) -> RestoreOutcome.ALREADY_PRESENT
            !directory.exists(source) -> RestoreOutcome.NOT_FOUND
            else -> when (val image = recoveredImage(directory, source, seed, row)) {
                null -> RestoreOutcome.UNREADABLE
                else ->
                    if (LocalAttachmentSeal.writeAtomically(local, LocalAttachmentSeal.seal(key, image))) {
                        RestoreOutcome.RESTORED
                    } else {
                        RestoreOutcome.FAILED
                    }
            }
        }
    }

    /**
     * The image this row describes, or null if the folder cannot supply it.
     *
     * Three ways it can fail and they are deliberately one answer here: the
     * file cannot be read, it does not open (wrong phrase, damaged, or **named
     * after another attachment** — the id is bound into the header), or it
     * opens and is not what the row says it is. The last is the final check
     * between the folder and a receipt shown against the wrong entry.
     */
    private fun recoveredImage(
        directory: BackupFolder,
        source: String,
        seed: ByteArray,
        row: AttachmentEntity,
    ): ByteArray? {
        val bytes = directory.read(source) ?: return null
        val image = when (val read = LfbaContainer.read(bytes, seed, row.id)) {
            is LfbaResult.Failure -> null
            is LfbaResult.Success -> read.image
        }
        return image?.takeIf { LocalAttachmentSeal.sha256(it) == row.sha256 }
    }

    /**
     * Verify the file that **landed**, not the bytes in hand (§7): the folder
     * reads the written copy back and this checks *that* opens to the image.
     *
     * **Kept despite being unexercised, and said out loud rather than implied.**
     * A mutation sweep replaced this verification with `true` and reddened
     * nothing: making a just-written file fail its own decrypt needs fault
     * injection (a truncated write, a full disk), which no test here performs.
     * It stays because §7 requires a backup to be verified before it counts,
     * and because the cost is one decrypt per *new* image; what it must not do
     * is read as though a test is watching it.
     */
    private fun writeVerified(
        directory: BackupFolder,
        target: String,
        sealed: ByteArray,
        seed: ByteArray,
        attachmentId: String,
        expected: ByteArray,
    ): Boolean = directory.writeVerified(target, sealed) { landed ->
        when (val read = LfbaContainer.read(landed, seed, attachmentId)) {
            is LfbaResult.Failure -> false
            is LfbaResult.Success -> read.image.contentEquals(expected)
        }
    }

    /**
     * Is [target] a copy this phrase can open? Header only -- no decryption and
     * no reading the image: the difference between a pass proportional to new
     * receipts and one proportional to all of them.
     */
    private fun isCurrentCopy(directory: BackupFolder, target: String, seed: ByteArray): Boolean {
        val header = directory.readPrefix(target, HEADER_PROBE_BYTES) ?: return false
        return LfbaContainer.sealedWith(header, seed)
    }

    private fun isIntactLocally(local: File, key: ByteArray, sha256: String): Boolean {
        if (!local.isFile) return false
        val plaintext = runCatching { local.readBytes() }.getOrNull()
            ?.let { LocalAttachmentSeal.open(key, it) }
            ?: return false
        return LocalAttachmentSeal.sha256(plaintext) == sha256
    }

    private fun fileNameFor(attachmentId: String): String = "$attachmentId.$EXTENSION"

    public companion object {
        /**
         * The subfolder of the user's backup tree that holds sealed images.
         *
         * A folder rather than loose files beside the `.lfbk` so that a user
         * looking at their backup location sees one `.lfbk` and one folder,
         * rather than a `.lfbk` lost among hundreds of receipts.
         */
        public const val IMAGES_DIRECTORY: String = "attachments"

        /**
         * Not `jpg`: these bytes are ciphertext. Distinct from the local
         * `.lfa` because the two are sealed with different keys and only one
         * of them can be opened by the phrase alone.
         */
        public const val EXTENSION: String = "lfba"

        /**
         * Enough for the largest header this format can produce: fixed fields
         * plus a 256-byte attachment id, well under 512.
         */
        private const val HEADER_PROBE_BYTES = 512
    }
}
