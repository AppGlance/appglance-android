package app.appglance

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration rejects values that could only be mistakes - a zero heartbeat is a tight send
 * loop, a zero batch size could never send - at construction, where the stack trace points at the
 * caller, instead of misbehaving quietly in the field.
 */
class ConfigurationTest {

    private fun config(
        flushInterval: Duration = 10.seconds,
        maxBatchSize: Int = 20,
        heartbeatInterval: Duration = 60.seconds,
        sessionTimeout: Duration = 300.seconds,
    ) = AppGlance.Configuration(
        apiKey = "glance_live_test",
        flushInterval = flushInterval,
        maxBatchSize = maxBatchSize,
        heartbeatInterval = heartbeatInterval,
        sessionTimeout = sessionTimeout,
    )

    private fun rejects(field: String, build: () -> AppGlance.Configuration) {
        val thrown = assertThrows(IllegalArgumentException::class.java) { build() }
        assertTrue(
            "the message must name the offending option; got: ${thrown.message}",
            thrown.message.orEmpty().contains(field),
        )
    }

    @Test
    fun `a zero or negative heartbeat interval is refused, it would be a tight send loop`() {
        rejects("heartbeatInterval") { config(heartbeatInterval = Duration.ZERO) }
        rejects("heartbeatInterval") { config(heartbeatInterval = (-30).seconds) }
    }

    @Test
    fun `the heartbeat floor is fifteen seconds exactly`() {
        rejects("heartbeatInterval") { config(heartbeatInterval = 14.seconds) }
        config(heartbeatInterval = 15.seconds)   // the boundary is accepted
    }

    @Test
    fun `the batch size must fit what the ingest accepts`() {
        rejects("maxBatchSize") { config(maxBatchSize = 0) }
        rejects("maxBatchSize") { config(maxBatchSize = -5) }
        rejects("maxBatchSize") { config(maxBatchSize = 501) }
        config(maxBatchSize = 1)                 // both boundaries are accepted
        config(maxBatchSize = 500)
    }

    @Test
    fun `the flush interval must be positive`() {
        rejects("flushInterval") { config(flushInterval = Duration.ZERO) }
        rejects("flushInterval") { config(flushInterval = (-10).seconds) }
    }

    @Test
    fun `the session timeout must be positive`() {
        rejects("sessionTimeout") { config(sessionTimeout = Duration.ZERO) }
        rejects("sessionTimeout") { config(sessionTimeout = (-1).seconds) }
    }

    @Test
    fun `the defaults and the plain overload construct without complaint`() {
        AppGlance.Configuration(apiKey = "glance_live_test")
        AppGlance.Configuration(apiKey = "glance_live_test", debug = true)
    }
}
