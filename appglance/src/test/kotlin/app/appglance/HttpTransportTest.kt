package app.appglance

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** The real transport against a real (local) HTTP server: headers, body, status passthrough, and "no response". */
class HttpTransportTest {

    private class Received(val method: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Received>()

    @Volatile private var respondWith = 202

    @Volatile private var respondWithRetryAfter: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            received += Received(exchange.requestMethod, headers, body)
            respondWithRetryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
            val reply = "{\"accepted\":1,\"rejected\":0}".toByteArray()
            exchange.sendResponseHeaders(respondWith, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private val endpoint get() = "http://127.0.0.1:${server.address.port}/v1/events"

    @Test
    fun `posts the JSON array with the write key, content type and user agent`() {
        val transport = HttpTransport(endpoint, "glance_live_abc")
        val clock = FakeClock()
        val client = makeClient(
            InMemoryPlatform(),
            clock,
            FakeScheduler(clock),
            transport,
            testConfiguration(appId = "app.test", apiKey = "glance_live_abc", endpoint = endpoint),
        )
        client.track("paywall.viewed", mapOf("source" to "settings"))
        client.flush()

        val request = received.single()
        assertEquals("POST", request.method)
        assertEquals("Bearer glance_live_abc", request.headers["authorization"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("AppGlance-Android/${AppGlance.VERSION}", request.headers["user-agent"])
        assertTrue("fixed length, not chunked", request.headers.containsKey("content-length"))
        val row = JSONArray(request.body).getJSONObject(0)
        assertEquals("paywall.viewed", row.getString("signal"))
        assertEquals("settings", row.getJSONObject("metadata").getString("source"))
        assertEquals("Android", row.getString("os_name"))
        assertTrue("a successful send clears the queue", client.pendingSignals().isEmpty())
    }

    @Test
    fun `passes the status code through`() {
        val transport = HttpTransport(endpoint, "k")
        respondWith = 202
        assertEquals(202, transport.send("[]".toByteArray()))
        respondWith = 429
        assertEquals(429, transport.send("[]".toByteArray()))
        respondWith = 401
        assertEquals(401, transport.send("[]".toByteArray()))
        respondWith = 500
        assertEquals(500, transport.send("[]".toByteArray()))
        assertEquals(4, received.size)
    }

    @Test
    fun `a numeric Retry-After is surfaced and anything else is ignored`() {
        val transport = HttpTransport(endpoint, "k")
        respondWith = 429
        respondWithRetryAfter = "30"
        assertEquals(429, transport.send("[]".toByteArray()))
        assertEquals(30L, transport.lastRetryAfterSeconds())

        // The HTTP-date form is legal but not worth a parser; the client just backs off normally.
        respondWithRetryAfter = "Wed, 21 Oct 2026 07:28:00 GMT"
        transport.send("[]".toByteArray())
        assertEquals(null, transport.lastRetryAfterSeconds())

        // And a response without the header leaves nothing stale behind.
        respondWithRetryAfter = null
        respondWith = 202
        transport.send("[]".toByteArray())
        assertEquals(null, transport.lastRetryAfterSeconds())
    }

    /**
     * `accepted` is how the ingest says it took fewer rows than were sent - what an account past
     * its grace ceiling gets with a 202 - so the client must be able to read it. A body that does
     * not carry it says nothing about the batch, which is not the same as "none were taken".
     */
    @Test
    fun `the accepted count is read from the response body when it carries one`() {
        assertEquals(3, HttpTransport.acceptedIn("""{"accepted":3,"heartbeat_interval":240}"""))
        assertEquals(0, HttpTransport.acceptedIn("""{"accepted": 0, "rejected": 2}"""))
        assertEquals(null, HttpTransport.acceptedIn("{}"))

        val transport = HttpTransport(endpoint, "k")
        respondWith = 202
        transport.send("[]".toByteArray())
        assertEquals("the local server answers {\"accepted\":1}", 1, transport.lastAcceptedCount())
        respondWith = 500
        transport.send("[]".toByteArray())
        assertEquals("and a failure leaves no stale count behind", null, transport.lastAcceptedCount())
    }

    @Test
    fun `no server means no response, never an exception`() {
        val port = server.address.port
        server.stop(0)
        val transport = HttpTransport("http://127.0.0.1:$port/v1/events", "k", timeoutMillis = 2_000)
        // NEVER_CONNECTED rather than a bare "no response": the connection was refused, so the
        // batch provably never reached a server - which is what lets the client retry presence
        // pings. A read timeout answers NO_RESPONSE instead: there the request may have landed and
        // only the reply gone missing.
        assertEquals(Transport.NEVER_CONNECTED, transport.send("[]".toByteArray()))
        assertEquals(Transport.NEVER_CONNECTED, HttpTransport("not a url", "k").send("[]".toByteArray()))
    }

    @Test
    fun `a failed TLS handshake is never connected, not an ambiguous no response`() {
        // A plain HTTP server behind an https:// URL: the handshake fails (or times out) before
        // any request is written, so nothing on the other end could have counted the batch.
        val transport = HttpTransport("https://127.0.0.1:${server.address.port}/v1/events", "k", timeoutMillis = 1_000)
        assertEquals(Transport.NEVER_CONNECTED, transport.send("[]".toByteArray()))
        assertTrue("no request was ever read by the server", received.isEmpty())
    }
}
