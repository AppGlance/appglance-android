package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration clamps values that could only be mistakes - a zero heartbeat is a tight send loop,
 * a zero batch size could never send - instead of refusing them: an app that ships a bad number
 * keeps working, with a cadence it can live with, rather than crashing on the call that configures
 * its analytics. The Swift SDK holds the same fields to the same bounds.
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

    @Test
    fun `a heartbeat interval that would be a tight send loop is lifted to the floor`() {
        assertEquals(15.seconds, config(heartbeatInterval = Duration.ZERO).heartbeatInterval)
        assertEquals(15.seconds, config(heartbeatInterval = (-30).seconds).heartbeatInterval)
        assertEquals(15.seconds, config(heartbeatInterval = 14.seconds).heartbeatInterval)
        assertEquals("the boundary stands", 15.seconds, config(heartbeatInterval = 15.seconds).heartbeatInterval)
        assertEquals(1.hours, config(heartbeatInterval = Duration.INFINITE).heartbeatInterval)
    }

    @Test
    fun `the batch size is held to what the ingest accepts`() {
        assertEquals(1, config(maxBatchSize = 0).maxBatchSize)
        assertEquals(1, config(maxBatchSize = -5).maxBatchSize)
        assertEquals(500, config(maxBatchSize = 501).maxBatchSize)
        assertEquals(1, config(maxBatchSize = 1).maxBatchSize)
        assertEquals(500, config(maxBatchSize = 500).maxBatchSize)
    }

    @Test
    fun `the flush interval is positive and bounded`() {
        assertEquals(1.seconds, config(flushInterval = Duration.ZERO).flushInterval)
        assertEquals(1.seconds, config(flushInterval = (-10).seconds).flushInterval)
        assertEquals(1.hours, config(flushInterval = 6.hours).flushInterval)
        assertEquals(10.seconds, config().flushInterval)
    }

    @Test
    fun `the session timeout is positive and bounded`() {
        assertEquals(1.seconds, config(sessionTimeout = Duration.ZERO).sessionTimeout)
        assertEquals(1.seconds, config(sessionTimeout = (-1).seconds).sessionTimeout)
        assertEquals(24.hours, config(sessionTimeout = 48.hours).sessionTimeout)
        assertEquals(300.seconds, config().sessionTimeout)
    }

    @Test
    fun `the defaults and the plain overload construct without complaint`() {
        AppGlance.Configuration(apiKey = "glance_live_test")
        AppGlance.Configuration(apiKey = "glance_live_test", debug = true)
    }
}
