package com.ledgerflow.core.drive

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.drive.DriveCall
import com.ledgerflow.core.domain.drive.DriveFiles
import com.ledgerflow.core.domain.drive.RemoteFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drive's REST API v3, the four calls ADR-0034 §4 names, over
 * `HttpURLConnection` with a bearer token. No Google API client: it is several
 * megabytes for four calls (ADR-0004's reasoning).
 *
 * [base] is `https://www.googleapis.com`; tests point it at an in-process
 * server. Every call runs on the IO dispatcher and answers a [DriveCall]
 * rather than throwing: a network error is [DriveCall.Failed] (retryable), a
 * 401 is [DriveCall.Unauthorized].
 */
public class HttpDriveFiles internal constructor(
    private val base: String,
    private val io: CoroutineDispatcher,
) : DriveFiles {

    @Inject
    public constructor(@IoDispatcher io: CoroutineDispatcher) : this(GOOGLE_APIS, io)

    override suspend fun folder(token: String, name: String, parentId: String?): DriveCall<String> =
        when (val found = findFolder(token, name, parentId)) {
            is DriveCall.Ok -> found.value?.let { DriveCall.Ok(it) } ?: createFolder(token, name, parentId ?: "root")
            is DriveCall.Unauthorized -> found
            is DriveCall.Failed -> found
        }

    override suspend fun findFolder(token: String, name: String, parentId: String?): DriveCall<String?> {
        val parent = parentId ?: "root"
        val query = "name = '${name.quoted()}' and mimeType = '$FOLDER_MIME' " +
            "and '$parent' in parents and trashed = false"
        return request(token, "GET", "/drive/v3/files?q=${query.encoded()}&fields=files(id)")
            .map { page -> page.files().firstOrNull()?.string("id") }
    }

    override suspend fun download(token: String, fileId: String): DriveCall<ByteArray> = withContext(io) {
        try {
            val connection = (URL("$base/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                }
            try {
                when (val code = connection.responseCode) {
                    in OK_CODES -> DriveCall.Ok(connection.inputStream.use { it.readBytes() })
                    HttpURLConnection.HTTP_UNAUTHORIZED -> DriveCall.Unauthorized
                    else -> DriveCall.Failed(
                        retryable = code == TOO_MANY_REQUESTS || code >= SERVER_ERROR,
                        reason = "HTTP $code",
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (failure: IOException) {
            DriveCall.Failed(retryable = true, reason = failure.javaClass.simpleName)
        }
    }

    private suspend fun createFolder(token: String, name: String, parent: String): DriveCall<String> {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        return request(token, "POST", "/drive/v3/files?fields=id", Body.Json(metadata)).map { it.string("id") }
    }

    /** Every page: a folder of receipts outgrows one page of [PAGE_SIZE]. */
    override suspend fun list(token: String, folderId: String): DriveCall<List<RemoteFile>> {
        val query = "'$folderId' in parents and trashed = false"
        val fields = "nextPageToken,files(id,name,md5Checksum,size)"
        val first = "/drive/v3/files?q=${query.encoded()}&fields=$fields&pageSize=$PAGE_SIZE"
        val all = mutableListOf<RemoteFile>()
        var path: String? = first
        while (path != null) {
            val page = when (val answer = request(token, "GET", path)) {
                is DriveCall.Ok -> answer.value
                DriveCall.Unauthorized -> return DriveCall.Unauthorized
                is DriveCall.Failed -> return answer
            }
            all += page.files().map { it.toRemoteFile() }
            path = page["nextPageToken"]?.jsonPrimitive?.content?.let { "$first&pageToken=${it.encoded()}" }
        }
        return DriveCall.Ok(all)
    }

    override suspend fun upload(
        token: String,
        folderId: String,
        name: String,
        bytes: ByteArray,
    ): DriveCall<RemoteFile> {
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(JsonPrimitive(folderId)) })
        }
        return request(
            token,
            "POST",
            "/upload/drive/v3/files?uploadType=multipart&fields=id,name,md5Checksum,size",
            Body.Multipart(metadata, bytes),
        ).map { it.toRemoteFile() }
    }

    override suspend fun delete(token: String, fileId: String): DriveCall<Unit> =
        request(token, "DELETE", "/drive/v3/files/$fileId").map { }

    private sealed interface Body {
        data class Json(val value: JsonObject) : Body

        class Multipart(val metadata: JsonObject, val content: ByteArray) : Body
    }

    private suspend fun request(
        token: String,
        method: String,
        path: String,
        body: Body? = null,
    ): DriveCall<JsonObject> =
        withContext(io) {
            try {
                val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    body?.let { connection.send(it) }
                    connection.answer()
                } finally {
                    connection.disconnect()
                }
            } catch (failure: IOException) {
                DriveCall.Failed(retryable = true, reason = failure.javaClass.simpleName)
            }
        }

    private fun HttpURLConnection.send(body: Body) {
        doOutput = true
        val bytes = when (body) {
            is Body.Json -> {
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                body.value.toString().toByteArray()
            }
            is Body.Multipart -> {
                setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
                multipart(body.metadata, body.content)
            }
        }
        setFixedLengthStreamingMode(bytes.size)
        outputStream.use { it.write(bytes) }
    }

    private fun HttpURLConnection.answer(): DriveCall<JsonObject> {
        val code = responseCode
        val stream = if (code in OK_CODES) inputStream else errorStream
        val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
        return when {
            code in OK_CODES -> DriveCall.Ok(parsed(text))
            code == HttpURLConnection.HTTP_UNAUTHORIZED -> DriveCall.Unauthorized
            else -> DriveCall.Failed(
                retryable = code == TOO_MANY_REQUESTS || code >= SERVER_ERROR,
                reason = "HTTP $code",
            )
        }
    }

    internal companion object {
        const val GOOGLE_APIS = "https://www.googleapis.com"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val BOUNDARY = "ledgerflow-drive-boundary"
        private const val TIMEOUT_MS = 60_000
        private const val PAGE_SIZE = 1000
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500
        private val OK_CODES = 200..299

        /** A 2xx body; a DELETE answers with none. */
        fun parsed(text: String): JsonObject =
            if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

        /** Drive's multipart upload: the metadata part, then the bytes, unchanged. */
        fun multipart(metadata: JsonObject, content: ByteArray): ByteArray {
            val head = "--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$BOUNDARY\r\nContent-Type: application/octet-stream\r\n\r\n"
            return head.toByteArray() + content + "\r\n--$BOUNDARY--\r\n".toByteArray()
        }
    }
}

private fun <T, R> DriveCall<T>.map(transform: (T) -> R): DriveCall<R> = when (this) {
    is DriveCall.Ok -> DriveCall.Ok(transform(value))
    DriveCall.Unauthorized -> DriveCall.Unauthorized
    is DriveCall.Failed -> this
}

private fun JsonObject.files(): List<JsonObject> = this["files"]?.jsonArray?.map { it.jsonObject }.orEmpty()

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.toRemoteFile() = RemoteFile(
    id = string("id"),
    name = string("name"),
    md5 = this["md5Checksum"]?.jsonPrimitive?.content,
    size = this["size"]?.jsonPrimitive?.content?.toLongOrNull(),
)

/** A name inside a Drive query's single quotes. */
private fun String.quoted(): String = replace("\\", "\\\\").replace("'", "\\'")

private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")
