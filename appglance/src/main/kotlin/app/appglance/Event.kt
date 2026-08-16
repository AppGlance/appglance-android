package app.appglance

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * One event, shaped as the ingest API sees it - one element of the JSON array a batch is made of.
 * Field names on the wire are snake_case; `id` and `created_at` are assigned server-side.
 */
internal data class Event(
    /**
     * Client-minted UUID. The server ignores a replay of the same `(app_id, event_id)`, which is
     * what makes retrying a batch safe.
     */
    val eventId: String,
    /**
     * The session this event belongs to (minted at each `session.start`); null for events
     * recorded before an install's first session, such as `install` itself.
     */
    val sessionId: String?,
    val appId: String,
    val userId: String,
    val signal: String,
    val appVersion: String,
    val osName: String,
    val osVersion: String,
    val environment: String,
    val country: String?,
    /** When the app made the call - epoch milliseconds, UTC. */
    val clientTs: Long,
    val metadata: Map<String, String>?,
)

/**
 * The one JSON dialect the SDK speaks, on the wire and on disk - so what is persisted is exactly
 * what would have been sent. Timestamps are ISO-8601 UTC with milliseconds when writing (events
 * fired within the same second keep their order), and either form when reading.
 */
internal object EventCoding {
    fun encode(events: List<Event>): String {
        val array = JSONArray()
        for (event in events) array.put(encodeOne(event))
        return array.toString()
    }

    fun encodeBytes(events: List<Event>): ByteArray = encode(events).toByteArray(Charsets.UTF_8)

    /** Lenient: a row that cannot be read is skipped rather than failing the whole queue. */
    fun decode(json: String): List<Event> {
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<Event>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            decodeOne(row)?.let(out::add)
        }
        return out
    }

    private fun encodeOne(e: Event): JSONObject {
        val o = JSONObject()
        o.put("event_id", e.eventId)
        e.sessionId?.let { o.put("session_id", it) }
        o.put("app_id", e.appId)
        o.put("user_id", e.userId)
        o.put("signal", e.signal)
        o.put("app_version", e.appVersion)
        o.put("os_name", e.osName)
        o.put("os_version", e.osVersion)
        o.put("environment", e.environment)
        e.country?.let { o.put("country", it) }
        o.put("client_ts", Iso8601.format(e.clientTs))
        e.metadata?.let { m ->
            val meta = JSONObject()
            for ((k, v) in m) meta.put(k, v)
            o.put("metadata", meta)
        }
        return o
    }

    private fun decodeOne(o: JSONObject): Event? {
        fun str(key: String): String? = if (o.has(key) && !o.isNull(key)) o.optString(key) else null
        val ts = str("client_ts")?.let(Iso8601::parse) ?: return null
        val metadata = o.optJSONObject("metadata")?.let { m ->
            val map = LinkedHashMap<String, String>()
            for (key in m.keys()) if (!m.isNull(key)) map[key] = m.optString(key)
            map
        }
        return Event(
            eventId = str("event_id") ?: return null,
            sessionId = str("session_id"),
            appId = str("app_id") ?: return null,
            userId = str("user_id") ?: return null,
            signal = str("signal") ?: return null,
            appVersion = str("app_version") ?: "unknown",
            osName = str("os_name") ?: "Android",
            osVersion = str("os_version") ?: "unknown",
            environment = str("environment") ?: AppEnvironment.PRODUCTION.wireValue,
            country = str("country"),
            clientTs = ts,
            metadata = metadata,
        )
    }
}

/** ISO-8601 in UTC: `2023-11-14T22:13:20.250Z` out; that or `2023-11-14T22:13:20Z` in. */
internal object Iso8601 {
    private val utc = TimeZone.getTimeZone("UTC")
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = utc }
    private val pattern = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$""")

    fun format(epochMillis: Long): String = synchronized(formatter) { formatter.format(Date(epochMillis)) }

    fun parse(text: String): Long? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi, s, frac) = m.destructured
        val cal = GregorianCalendar(utc, Locale.US)
        cal.clear()
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        cal.set(Calendar.MILLISECOND, 0)
        val millis = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').substring(0, 3).toLong()
        return cal.timeInMillis + millis
    }
}
