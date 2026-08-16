package app.appglance

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCodingTest {

    private fun sample(metadata: Map<String, String>? = mapOf("k" to "v"), country: String? = "US") = Event(
        eventId = "0e37e055-7d1c-4b4f-9a54-8f3ac54f8f70",
        sessionId = "5b1c8d1e-2b7f-4f36-8b0f-1a2b3c4d5e6f",
        appId = "com.example.app",
        userId = "abc",
        signal = Signal.HEARTBEAT,
        appVersion = "1.0",
        osName = "Android",
        osVersion = "14",
        environment = AppEnvironment.PRODUCTION.wireValue,
        country = country,
        clientTs = 0L,
        metadata = metadata,
    )

    @Test
    fun `events encode snake_case fields and an ISO-8601 UTC timestamp`() {
        val json = EventCoding.encode(listOf(sample()))
        val row = JSONArray(json).getJSONObject(0)
        assertEquals("com.example.app", row.getString("app_id"))
        assertEquals("production", row.getString("environment"))
        assertEquals("US", row.getString("country"))
        assertEquals("1970-01-01T00:00:00.000Z", row.getString("client_ts"))
        assertEquals("v", row.getJSONObject("metadata").getString("k"))
        assertEquals("0e37e055-7d1c-4b4f-9a54-8f3ac54f8f70", row.getString("event_id"))
        assertEquals("5b1c8d1e-2b7f-4f36-8b0f-1a2b3c4d5e6f", row.getString("session_id"))
        assertEquals("Android", row.getString("os_name"))
        assertEquals("heartbeat", row.getString("signal"))
    }

    @Test
    fun `null metadata and country are omitted, not sent as null`() {
        val json = EventCoding.encode(listOf(sample(metadata = null, country = null)))
        assertFalse("null metadata should be omitted", json.contains("metadata"))
        assertFalse("null country should be omitted", json.contains("country"))
        val row = JSONArray(json).getJSONObject(0)
        assertFalse(row.has("metadata"))
        assertFalse(row.has("country"))
    }

    @Test
    fun `the coder writes fractional seconds and reads back both forms`() {
        val t = 1_700_000_000_250L
        val out = EventCoding.encode(listOf(sample().copy(clientTs = t)))
        assertTrue(out, out.contains("\"client_ts\":\"2023-11-14T22:13:20.250Z\""))

        val back = EventCoding.decode(out).single()
        assertEquals(t, back.clientTs)
        assertEquals(mapOf("k" to "v"), back.metadata)
        assertEquals("5b1c8d1e-2b7f-4f36-8b0f-1a2b3c4d5e6f", back.sessionId)

        val legacy = out.replace("22:13:20.250Z", "22:13:20Z")
        assertEquals(1_700_000_000_000L, EventCoding.decode(legacy).single().clientTs)
    }

    @Test
    fun `a broken row is skipped rather than losing the whole queue`() {
        val good = EventCoding.encode(listOf(sample()))
        val mixed = good.dropLast(1) + ",{\"signal\":\"orphan\"},\"not-an-object\"]"
        val decoded = EventCoding.decode(mixed)
        assertEquals(listOf(Signal.HEARTBEAT), decoded.map { it.signal })
        assertTrue(EventCoding.decode("this is not json").isEmpty())

        // A row without an event id cannot be retried safely, so it is not loaded either.
        val noId = good.replace("\"event_id\":\"0e37e055-7d1c-4b4f-9a54-8f3ac54f8f70\",", "")
        assertTrue(noId != good)
        assertTrue(EventCoding.decode(noId).isEmpty())
    }

    @Test
    fun `ISO-8601 parsing is strict about the shape and lenient about fraction length`() {
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_250L, Iso8601.parse("2023-11-14T22:13:20.25Z"))
        assertEquals(1_700_000_000_250L, Iso8601.parse("2023-11-14T22:13:20.250123Z"))
        assertNull(Iso8601.parse("2023-11-14 22:13:20"))
        assertNull(Iso8601.parse("yesterday"))
        assertNotNull(Iso8601.parse(Iso8601.format(System.currentTimeMillis())))
    }
}
