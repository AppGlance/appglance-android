package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * The real transport against the real ingest - skipped unless a throwaway app's write key is
 * provided, so the normal suite never touches the network:
 *
 * ```
 * APPGLANCE_SMOKE_KEY=glance_live_… ./gradlew :appglance:testDebugUnitTest --tests '*LiveIngestSmokeTest*'
 * ```
 *
 * It sends only silent, never-billable signals (heartbeats and one `user.identify`), tagged
 * with the platform-neutral environment names, so the run proves the wire contract end to end
 * (accepted by the Worker, `production` stored as `appstore`) without notifying anyone or
 * counting against a plan. Point it at a throwaway app of your own (`test.android.smoke`) and
 * remove that app afterwards.
 */
class LiveIngestSmokeTest {

    @Test
    fun `the ingest accepts what the Android SDK sends`() {
        val key = System.getenv("APPGLANCE_SMOKE_KEY").orEmpty()
        assumeTrue("set APPGLANCE_SMOKE_KEY to run against the live ingest", key.startsWith("glance_"))
        val endpoint = System.getenv("APPGLANCE_SMOKE_ENDPOINT").orEmpty().ifEmpty {
            AppGlance.Configuration.DEFAULT_ENDPOINT
        }
        val userId = System.getenv("APPGLANCE_SMOKE_USER").orEmpty().ifEmpty { "android-smoke-" + UUID.randomUUID() }

        fun event(signal: String, environment: AppEnvironment, metadata: Map<String, String>? = null) = Event(
            eventId = UUID.randomUUID().toString(),
            sessionId = "9d3f2c1e-1b2a-4c3d-8e9f-0a1b2c3d4e5f",
            appId = "test.android.smoke",
            userId = userId,
            signal = signal,
            appVersion = "0.1.0-smoke",
            osName = "Android",
            osVersion = "14",
            environment = environment.wireValue,
            country = "NO",
            clientTs = System.currentTimeMillis(),
            metadata = metadata,
        )
        val batch = listOf(
            event(Signal.HEARTBEAT, AppEnvironment.PRODUCTION),
            event(Signal.HEARTBEAT, AppEnvironment.EMULATOR),
            event(
                Signal.IDENTIFY,
                AppEnvironment.PRODUCTION,
                mapOf(
                    "plan" to "smoke",
                    UserProperty.NAME to "Android Smoke",
                ),
            ),
        )
        val status = HttpTransport(endpoint, key).send(EventCoding.encodeBytes(batch))
        assertEquals("the ingest stores the batch (user id $userId)", 202, status)
        println("[smoke] accepted; user_id=$userId event_ids=${batch.map { it.eventId }}")

        // A replay of the same batch must be idempotent (same event ids) - still a 202.
        assertEquals(202, HttpTransport(endpoint, key).send(EventCoding.encodeBytes(batch)))
        // A wrong key is a permanent rejection the client would drop, never retry.
        val rejected = HttpTransport(endpoint, "glance_live_" + "0".repeat(64)).send(EventCoding.encodeBytes(batch))
        assertTrue("got $rejected", Client.isPermanent(rejected))
    }
}
