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

    override fun send(body: ByteArray): Int {
        retryAfterSeconds = null
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
            // Read and discard the body so the connection can be reused.
            try {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { stream ->
                    val sink = ByteArray(1024)
                    while (stream.read(sink) != -1) { /* drain */ }
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
}
