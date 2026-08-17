package com.ledgerflow.core.crypto

import java.io.File

/** Persistence for the wrapped-DEK blobs. */
public interface WrappedDekStore {
    public fun read(kekId: KekId): ByteArray?
    public fun write(kekId: KekId, bytes: ByteArray): Boolean
    public fun exists(kekId: KekId): Boolean
    public fun delete(kekId: KekId)
}

/**
 * File-backed store.
 *
 * [directory] **must** be inside `filesDir` (Law 5). `cacheDir` and external
 * storage are OS-reclaimable; losing a wrapped-DEK blob there would strand the
 * user on the Recovery screen for no reason.
 *
 * Writes are atomic: temp file -> fsync -> rename. A half-written
 * `wrapped_dek_ks.bin` after a crash would look exactly like a corrupt blob,
 * and the fsync is what makes the rename meaningful rather than merely
 * ordered-in-the-page-cache.
 */
public class FileWrappedDekStore(
    directoryProvider: () -> File,
) : WrappedDekStore {

    /** Convenience for tests and callers that already hold a resolved path. */
    public constructor(directory: File) : this({ directory })

    /**
     * Resolved on first use, not at construction.
     *
     * `Context.getFilesDir()` stats the data directory, and this object is built
     * during Hilt graph construction -- which happens on the main thread when the
     * first ViewModel is created. StrictMode caught exactly that as a
     * `DiskReadViolation`, which is the tripwire doing its job; deferring the
     * lookup to the first read or write puts it on the IO dispatcher where the
     * rest of the unlock flow already runs.
     */
    private val directory: File by lazy(directoryProvider)

    override fun read(kekId: KekId): ByteArray? =
        file(kekId).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    override fun write(kekId: KekId, bytes: ByteArray): Boolean = runCatching {
        if (!directory.exists()) directory.mkdirs()
        val target = file(kekId)
        val temp = File(directory, "${target.name}.tmp")

        temp.outputStream().use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        // File.renameTo is not guaranteed atomic everywhere, but on a single
        // Android filesystem it is a rename(2) and therefore is.
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    override fun exists(kekId: KekId): Boolean = file(kekId).isFile

    override fun delete(kekId: KekId) {
        runCatching { file(kekId).delete() }
    }

    private fun file(kekId: KekId): File = File(directory, kekId.fileName)
}
