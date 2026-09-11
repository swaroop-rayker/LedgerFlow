package com.ledgerflow.core.data.ingest

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where receipt images live on disk, in one place (ADR-0023, Law 5).
 *
 * ## Why this is a class and not a constant
 *
 * Two callers need it and they are in different layers.
 * `DefaultAttachmentRepository` writes and reads; `DefaultLedgerRepository`
 * **unlinks**, because the purge is the only thing in the app that hard-deletes
 * a `ledger_entry` and `ON DELETE CASCADE` takes the `attachment` row while
 * leaving the bytes.
 *
 * A second copy of the directory name in the purge would be a copy that can
 * drift, and the failure mode if it did is the worst kind: the purge would
 * report success, delete nothing, and nothing else in the app enumerates that
 * directory to notice. One owner of the path means the unlink cannot be
 * looking somewhere else.
 *
 * `filesDir`, never `cacheDir` (Law 5, enforced by `bannedApiCheck`) and never
 * external storage.
 */
@Singleton
public class AttachmentFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** `filesDir/attachments/`. Created on demand by the writer. */
    public fun directory(): File = File(context.filesDir, DIRECTORY)

    /** Resolves a stored **relative** `file_path` against [directory]. */
    public fun resolve(relativePath: String): File = File(directory(), relativePath)

    /**
     * Unlinks the files named by [relativePaths].
     *
     * @return how many were actually removed. Deliberately *not* the count
     *   asked for: a path whose file is already gone is not an error — a
     *   restore from a `.lfbk` moved without its sibling images produces
     *   exactly that — and reporting it as one would make an ordinary purge
     *   look broken.
     */
    public fun delete(relativePaths: List<String>): Int =
        relativePaths.count { path ->
            val file = resolve(path)
            file.isFile && file.delete()
        }

    /** Every sealed file present, for the Settings "N images, M MB" figure. */
    public fun all(): List<File> =
        directory().listFiles().orEmpty().filter { it.isFile && it.extension == EXTENSION }

    public companion object {
        private const val DIRECTORY = "attachments"

        /**
         * Not `jpg` or `png`: the bytes are ciphertext, and naming a sealed
         * file after the plaintext's format invites something — a media
         * scanner, a future contributor — to try to decode it.
         */
        public const val EXTENSION: String = "lfa"
    }
}
