package app.appglance

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The hosted ingest over plain `HttpURLConnection` - no HTTP client dependency to conflict with
 * the app's. One POST per batch; the status code is all the client needs (see `Client.drain`).
 */
internal class HttpTransport(
    private val endpoint: String,
    private val apiKey: String,
    private val timeoutMillis: Int = 15_000,
) : Transport {

    /** See [Transport.lastRetryAfterSeconds]; refreshed on every response, cleared when absent. */
    @Volatile
    private var retryAfterSeconds: Long? = null

    override fun lastRetryAfterSeconds(): Long? = retryAfterSeconds

    /** See [Transport.lastHeartbeatIntervalSeconds]; refreshed on every 2xx, cleared otherwise. */
    @Volatile
    private var heartbeatIntervalSeconds: Long? = null

    override fun lastHeartbeatIntervalSeconds(): Long? = heartbeatIntervalSeconds

    /** See [Transport.lastAcceptedCount]; refreshed on every 2xx, cleared otherwise. */
    @Volatile
    private var acceptedCount: Int? = null

    override fun lastAcceptedCount(): Int? = acceptedCount

    override fun send(body: ByteArray): Int {
        retryAfterSeconds = null
        heartbeatIntervalSeconds = null
        acceptedCount = null
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return Transport.NEVER_CONNECTED   // nothing was opened, so nothing was sent
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            // A redirect would turn the POST into a GET; report the 3xx and let the client keep the batch.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "AppGlance-Android/${BuildConfig.VERSION}")
            // Fixed length, so the ingest's size gate sees a Content-Length instead of chunking.
            connection.setFixedLengthStreamingMode(body.size)
            try {
                connection.connect()
            } catch (_: IOException) {
                // Offline, DNS failure, refused, unreachable, or a timeout while connecting or in
                // the TLS handshake: no request reached a server, so nothing on the other end
                // could have counted it. That is what lets the client retry presence pings.
                return Transport.NEVER_CONNECTED
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            // Only the numeric form; an HTTP-date here is vanishingly rare and not worth a parser.
            retryAfterSeconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            // Read the body (so the connection can be reused) and, on a 2xx, keep the two fields
            // the client cares about: the ingest answers `{"accepted": n, ...}` and may add
            // `heartbeat_interval` (seconds). A pattern match rather than a JSON parser: the SDK
            // carries no JSON dependency, both fields are bare integers, and a body carrying
            // neither is simply not a hint - the batch was accepted either way.
            try {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { stream ->
                    val text = String(stream.readBytes(), Charsets.UTF_8)
                    if (status in 200..299) {
                        heartbeatIntervalSeconds = heartbeatIntervalIn(text)
                        acceptedCount = acceptedIn(text)
                    }
                }
            } catch (_: IOException) {
                // The status is what matters.
            }
            return status
        } catch (_: IOException) {
            // Failed once the connection existed - a timeout, or a connection dropped part-way:
            // the batch may have been applied and the reply lost on the way back.
            return Transport.NO_RESPONSE
        } catch (_: RuntimeException) {
            return Transport.NO_RESPONSE
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        private val HEARTBEAT_INTERVAL = Regex(""""heartbeat_interval"\s*:\s*"?(\d+)""")
        private val ACCEPTED = Regex(""""accepted"\s*:\s*"?(\d+)""")

        /** `heartbeat_interval` from an ingest response body, if it carries one. */
        internal fun heartbeatIntervalIn(body: String): Long? =
            HEARTBEAT_INTERVAL.find(body)?.groupValues?.get(1)?.toLongOrNull()

        /** `accepted` from an ingest response body, if it carries one. */
        internal fun acceptedIn(body: String): Int? = ACCEPTED.find(body)?.groupValues?.get(1)?.toIntOrNull()
    }
}
