package com.ledgerflow.core.rates

import android.content.Context
import com.ledgerflow.core.common.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The ECB's table over `HttpURLConnection`, and its cache (ADR-0036 §2, §5).
 *
 * **What the request carries is nothing about the user:** a `GET` of one
 * fixed address, no query string, no cookie, and a `User-Agent` of
 * `LedgerFlow` instead of Android's default, which names the phone's model and
 * build. A redirect is not followed. The answer is read up to [MAX_BYTES] and
 * parsed strictly; only a table that parses replaces the cached one.
 *
 * **The cache is `filesDir/rates/`** (Law 5), written `.tmp` then moved into
 * place, so a crash mid-write leaves the old table rather than half a new one.
 * It is public data: not in the vault, not in a backup.
 *
 * **Constructing it touches no disk (BUG92, as BUG51 before it).** Hilt builds
 * it on the main thread when the converter opens, and resolving
 * `context.filesDir` there is a disk read that StrictMode's penaltyDeath turns
 * into a crash in every debug build. The folder is a function, resolved on
 * first use, which is always inside `withContext(io)`.
 *
 * [address] and [directory] are the ECB's and `filesDir/rates/`; tests point
 * them at an in-process server and a temporary folder.
 */
@Singleton
public class EcbRatesSource internal constructor(
    private val address: String,
    directory: () -> File,
    private val io: CoroutineDispatcher,
) : RatesSource {

    @Inject
    public constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(EcbRates.ADDRESS, { File(context.filesDir, DIRECTORY) }, io)

    private val lock = Mutex()

    /** Resolved the first time a read or a write needs it, on [io]. */
    private val folder: File by lazy(directory)
    private val file get() = File(folder, FILE_NAME)

    override suspend fun cached(): RateTable? = withContext(io) {
        lock.withLock { file.takeIf { it.isFile }?.readBytes()?.let(EcbRates::parse) }
    }

    override suspend fun fetch(): RatesFetch = withContext(io) {
        val bytes = when (val answer = request()) {
            is Answer.Body -> answer.bytes
            is Answer.Fail -> return@withContext RatesFetch.Failed(answer.reason)
        }
        val table = EcbRates.parse(bytes) ?: return@withContext RatesFetch.Failed(FetchFailure.MALFORMED)
        lock.withLock { store(bytes) }
        RatesFetch.Fetched(table)
    }

    private sealed interface Answer {
        class Body(val bytes: ByteArray) : Answer

        class Fail(val reason: FetchFailure) : Answer
    }

    private fun request(): Answer = try {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            when {
                connection.responseCode != HttpURLConnection.HTTP_OK -> Answer.Fail(FetchFailure.REFUSED)
                connection.contentLengthLong > MAX_BYTES -> Answer.Fail(FetchFailure.TOO_LARGE)
                else -> connection.inputStream.use(::readCapped)
                    ?.let(Answer::Body)
                    ?: Answer.Fail(FetchFailure.TOO_LARGE)
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        Answer.Fail(FetchFailure.OFFLINE)
    }

    /** The whole body, or `null` once it passes [MAX_BYTES], whatever Content-Length claimed. */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
    }

    private fun store(bytes: ByteArray) {
        folder.mkdirs()
        val temporary = File(folder, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    internal companion object {
        const val DIRECTORY = "rates"
        const val FILE_NAME = "eurofxref-daily.xml"
        const val USER_AGENT = "LedgerFlow"

        /** 64 KiB; the table is about 2 KiB. */
        const val MAX_BYTES = 64 * 1024
        private const val TIMEOUT_MS = 15_000
        private const val BUFFER_BYTES = 8 * 1024
    }
}
