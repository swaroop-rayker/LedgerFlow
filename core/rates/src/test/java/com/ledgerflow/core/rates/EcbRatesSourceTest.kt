package com.ledgerflow.core.rates

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The request and the cache (ADR-0036 §2, §5) against an in-process HTTP
 * server from the JDK, as `:core:drive`'s tests do, so nothing leaves the
 * machine. What is checked is what the ECB would see: a `GET` of one path, no
 * query string, no cookie, `User-Agent: LedgerFlow`, and one request even when
 * the answer is a redirect.
 */
class EcbRatesSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var directory: File
    private val seen = mutableListOf<Seen>()
    private val answers = ArrayDeque<Answer>()
    private val live: ByteArray = requireNotNull(javaClass.getResource("/eurofxref-daily.xml")).readBytes()

    private class Seen(val method: String, val path: String, val query: String?, val headers: Map<String, String>)

    private class Answer(val code: Int, val body: ByteArray = ByteArray(0), val location: String? = null)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        directory = File(folder.root, "rates")
    }

    @After
    fun tearDown() = server.stop(0)

    private fun source(address: String = "http://127.0.0.1:${server.address.port}$PATH") =
        EcbRatesSource(address, directory, Dispatchers.IO)

    private fun handle(exchange: HttpExchange) {
        seen += Seen(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            query = exchange.requestURI.rawQuery,
            headers = exchange.requestHeaders.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() },
        )
        val answer = answers.removeFirstOrNull() ?: Answer(500)
        answer.location?.let { exchange.responseHeaders.add("Location", it) }
        exchange.sendResponseHeaders(answer.code, if (answer.body.isEmpty()) -1 else answer.body.size.toLong())
        if (answer.body.isNotEmpty()) exchange.responseBody.use { it.write(answer.body) }
        exchange.close()
    }

    @Test
    fun theRequest_carriesNothingAboutTheUser() = runBlocking<Unit> {
        answers += Answer(200, live)

        val fetched = source().fetch()

        assertThat(fetched).isInstanceOf(RatesFetch.Fetched::class.java)
        val request = seen.single()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo(PATH)
        assertThat(request.query).isNull()
        assertThat(request.headers["user-agent"]).isEqualTo("LedgerFlow")
        assertThat(request.headers).doesNotContainKey("cookie")
        assertThat(request.headers).doesNotContainKey("authorization")
    }

    @Test
    fun aFetchedTable_isCached_andReadBackWithoutANetwork() = runBlocking<Unit> {
        answers += Answer(200, live)
        val fetched = source().fetch() as RatesFetch.Fetched

        // A new instance, as after a restart, with nothing to answer it.
        val cached = source().cached()

        assertThat(cached).isEqualTo(fetched.table)
        assertThat(seen).hasSize(1)
        assertThat(File(directory, "eurofxref-daily.xml.tmp").exists()).isFalse()
    }

    @Test
    fun withNothingFetched_thereIsNoTable() = runBlocking<Unit> {
        assertThat(source().cached()).isNull()
    }

    /** A redirect to another host would be a second request; it is a failure instead (§2). */
    @Test
    fun aRedirect_isNotFollowed() = runBlocking<Unit> {
        answers += Answer(302, location = "http://127.0.0.1:${server.address.port}/elsewhere.xml")
        answers += Answer(200, live)

        val fetched = source().fetch()

        assertThat(fetched).isEqualTo(RatesFetch.Failed(FetchFailure.REFUSED))
        assertThat(seen).hasSize(1)
    }

    @Test
    fun anOversizedAnswer_isRefused_andTheLastGoodTableStays() = runBlocking<Unit> {
        answers += Answer(200, live)
        val good = (source().fetch() as RatesFetch.Fetched).table
        answers += Answer(200, ByteArray(EcbRatesSource.MAX_BYTES + 1) { 'x'.code.toByte() })

        val fetched = source().fetch()

        assertThat(fetched).isEqualTo(RatesFetch.Failed(FetchFailure.TOO_LARGE))
        assertThat(source().cached()).isEqualTo(good)
    }

    @Test
    fun aMalformedAnswer_isRefused_andTheLastGoodTableStays() = runBlocking<Unit> {
        answers += Answer(200, live)
        val good = (source().fetch() as RatesFetch.Fetched).table
        answers += Answer(200, "<html>Maintenance</html>".toByteArray())

        val fetched = source().fetch()

        assertThat(fetched).isEqualTo(RatesFetch.Failed(FetchFailure.MALFORMED))
        assertThat(source().cached()).isEqualTo(good)
    }

    @Test
    fun anErrorStatus_isRefused() = runBlocking<Unit> {
        answers += Answer(503)

        assertThat(source().fetch()).isEqualTo(RatesFetch.Failed(FetchFailure.REFUSED))
    }

    @Test
    fun noServer_isOffline() = runBlocking<Unit> {
        val closed = ServerSocket(0).use { it.localPort }

        val fetched = source("http://127.0.0.1:$closed$PATH").fetch()

        assertThat(fetched).isEqualTo(RatesFetch.Failed(FetchFailure.OFFLINE))
    }

    /** The production address is the ADR's, over HTTPS, with no query string. */
    @Test
    fun theAddress_isTheEcbsOverHttps() {
        assertThat(EcbRates.ADDRESS).isEqualTo("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml")
    }

    private companion object {
        const val PATH = "/stats/eurofxref/eurofxref-daily.xml"
    }
}
