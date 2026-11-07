package com.ledgerflow.core.drive

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.drive.DriveCall
import com.ledgerflow.core.domain.drive.RemoteFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Drive's four calls (ADR-0034 §4) against an in-process HTTP server from the
 * JDK, so no request leaves the machine and no test dependency is added. What
 * is checked is what Drive would see: the method, the path and query, the
 * bearer token, and a multipart body that carries the file's bytes unchanged.
 */
class HttpDriveFilesTest {

    private lateinit var server: HttpServer
    private lateinit var drive: HttpDriveFiles
    private val seen = mutableListOf<Seen>()
    private val answers = ArrayDeque<Answer>()

    private class Seen(val method: String, val path: String, val query: String, val auth: String?, val body: ByteArray)

    private data class Answer(val code: Int, val body: String = "")

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        drive = HttpDriveFiles("http://127.0.0.1:${server.address.port}", Dispatchers.IO)
    }

    @After
    fun tearDown() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val uri = exchange.requestURI
        seen += Seen(
            method = exchange.requestMethod,
            path = uri.path,
            query = URLDecoder.decode(uri.rawQuery.orEmpty(), "UTF-8"),
            auth = exchange.requestHeaders.getFirst("Authorization"),
            body = exchange.requestBody.readBytes(),
        )
        val answer = answers.removeFirstOrNull() ?: Answer(500)
        val bytes = answer.body.toByteArray()
        exchange.sendResponseHeaders(answer.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test
    fun anExistingFolder_isFound_andNotMadeAgain() = runBlocking<Unit> {
        answers += Answer(200, """{"files":[{"id":"folder-1"}]}""")

        val found = drive.folder("tok", "LedgerFlow backups", null)

        assertThat(found).isEqualTo(DriveCall.Ok("folder-1"))
        assertThat(seen).hasSize(1)
        assertThat(seen.single().auth).isEqualTo("Bearer tok")
        assertThat(seen.single().query).contains("name = 'LedgerFlow backups'")
        assertThat(seen.single().query).contains("'root' in parents")
    }

    @Test
    fun aMissingFolder_isMadeUnderItsParent() = runBlocking<Unit> {
        answers += Answer(200, """{"files":[]}""")
        answers += Answer(200, """{"id":"made"}""")

        val made = drive.folder("tok", "attachments", "folder-1")

        assertThat(made).isEqualTo(DriveCall.Ok("made"))
        assertThat(seen.last().method).isEqualTo("POST")
        val body = seen.last().body.decodeToString()
        assertThat(body).contains("\"name\":\"attachments\"")
        assertThat(body).contains("\"parents\":[\"folder-1\"]")
        assertThat(body).contains("application/vnd.google-apps.folder")
    }

    /** The bytes go up exactly as they are: no encoding, no re-encryption (ADR-0034 §1). */
    @Test
    fun anUpload_carriesTheBytesUnchanged_andReturnsDrivesChecksum() = runBlocking<Unit> {
        val content = ByteArray(256) { it.toByte() }
        answers += Answer(
            200,
            """{"id":"f1","name":"ledgerflow-20261107-030200.lfbk","md5Checksum":"abc","size":"256"}""",
        )

        val sent = drive.upload("tok", "folder-1", "ledgerflow-20261107-030200.lfbk", content)

        assertThat(sent).isEqualTo(DriveCall.Ok(RemoteFile("f1", "ledgerflow-20261107-030200.lfbk", "abc", 256)))
        val request = seen.single()
        assertThat(request.path).isEqualTo("/upload/drive/v3/files")
        assertThat(request.query).contains("uploadType=multipart")
        val body = request.body
        val start = body.indexOf(content)
        assertThat(start).isAtLeast(0)
        assertThat(body.decodeToString()).contains("\"parents\":[\"folder-1\"]")
    }

    @Test
    fun aListing_followsEveryPage() = runBlocking<Unit> {
        answers += Answer(200, """{"nextPageToken":"p2","files":[{"id":"a","name":"a.lfba"}]}""")
        answers += Answer(200, """{"files":[{"id":"b","name":"b.lfba","md5Checksum":"m"}]}""")

        val listed = drive.list("tok", "folder-1")

        assertThat(listed).isEqualTo(
            DriveCall.Ok(listOf(RemoteFile("a", "a.lfba", null, null), RemoteFile("b", "b.lfba", "m", null))),
        )
        assertThat(seen.last().query).contains("pageToken=p2")
    }

    @Test
    fun aDelete_withNoBody_isOk() = runBlocking<Unit> {
        answers += Answer(204)

        assertThat(drive.delete("tok", "f1")).isEqualTo(DriveCall.Ok(Unit))
        assertThat(seen.single().method).isEqualTo("DELETE")
        assertThat(seen.single().path).isEqualTo("/drive/v3/files/f1")
    }

    @Test
    fun aRefusedToken_isUnauthorized() = runBlocking<Unit> {
        answers += Answer(401, """{"error":{"code":401}}""")

        assertThat(drive.delete("tok", "f1")).isEqualTo(DriveCall.Unauthorized)
    }

    /** A 429 or a 5xx is worth trying again later; a 404 or 403 is not. */
    @Test
    fun serverTroubleRetries_andARefusalDoesNot() = runBlocking<Unit> {
        answers += Answer(503)
        answers += Answer(429)
        answers += Answer(404)

        assertThat((drive.delete("tok", "f1") as DriveCall.Failed).retryable).isTrue()
        assertThat((drive.delete("tok", "f1") as DriveCall.Failed).retryable).isTrue()
        assertThat((drive.delete("tok", "f1") as DriveCall.Failed).retryable).isFalse()
    }

    @Test
    fun noNetwork_isARetryableFailure_notACrash() = runBlocking<Unit> {
        server.stop(0)

        val answer = drive.delete("tok", "f1")

        assertThat((answer as DriveCall.Failed).retryable).isTrue()
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1
}
