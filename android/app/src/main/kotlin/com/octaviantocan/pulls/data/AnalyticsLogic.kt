package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The parts of the phone's usage analytics that need no Android: what an event looks like on the wire, which props
 * may go, how the queue is kept short, when a session starts again and how screen time is counted. `Analytics` runs
 * them; the JVM tests in `AnalyticsLogicTest` pin them. The server's `cleanEvent` (packages/core/src/analytics.ts)
 * checks the same things again before anything is written.
 */

/** One event as the server takes it at `POST /api/events`. [props] holds only strings, numbers and booleans. */
data class AnalyticsEvent(
    val name: String,
    val at: String,
    val version: String,
    val session: String,
    val device: String,
    val props: Map<String, Any> = emptyMap(),
) {
    /** The event as one JSON object on one line: the line kept in the queue file and sent in a batch. */
    fun encode(): String = buildJsonObject {
        put("name", name)
        put("at", at)
        put("app", "android")
        put("version", version)
        put("session", session)
        put("device", device)
        put("props", buildJsonObject {
            for ((key, value) in props) when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Number -> put(key, value)
            }
        })
    }.toString()
}

object AnalyticsRules {
    /** The queue keeps at most this many events, on disk and in memory; the oldest go first. */
    const val MAX_QUEUE = 5_000

    /** Once full, the queue drops this many more than it must, so a long time offline rewrites the file now and then, not on every event. */
    const val TRIM_SLACK = 500

    /** The server takes at most this many events in one call. */
    const val BATCH = 200

    /** Back after this long in the background, the phone starts a new session. */
    const val SESSION_GAP_MS = 30 * 60 * 1000L

    /** While the app is in front, the queue is sent this often. */
    const val FLUSH_EVERY_MS = 30_000L

    /** A string prop is cut to this; `stack` may be longer. */
    const val MAX_STRING = 200
    const val MAX_STACK = 2_000

    /** How many frames of a crash's stack go up. */
    const val CRASH_FRAMES = 10

    /**
     * Prop keys the server drops, so they are never sent: anything that could carry a secret or what was read or
     * typed (root `CONTRACTS`, analytics-what-not-how-much). Mirrors the server's list.
     */
    private val BANNED_KEY = Regex(
        "token|secret|password|apikey|api_key|authorization|cookie|credential|prompt|body|content|diff|patch|query|path|filename|text|answer|comment",
        RegexOption.IGNORE_CASE,
    )

    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun validId(id: String): Boolean = ID.matches(id)

    fun allowedKey(key: String): Boolean = key.isNotEmpty() && key.length <= 64 && !BANNED_KEY.containsMatchIn(key)

    /**
     * The props that may go: a banned key, a null, a number that is not finite or any other type is left out, and a
     * string is cut to [MAX_STRING] ([MAX_STACK] for `stack`).
     */
    fun cleanProps(props: Map<String, Any?>): Map<String, Any> = buildMap {
        for ((key, value) in props) {
            if (!allowedKey(key)) continue
            when (value) {
                is String -> put(key, value.take(if (key == "stack") MAX_STACK else MAX_STRING))
                is Boolean -> put(key, value)
                is Int, is Long, is Short, is Byte -> put(key, (value as Number).toLong())
                is Double -> if (value.isFinite()) put(key, value)
                is Float -> if (value.isFinite()) put(key, value.toDouble())
                else -> {}
            }
        }
    }

    /** How many of the oldest events to drop from a queue of [size]: none until it passes [MAX_QUEUE]. */
    fun overflow(size: Int): Int = if (size > MAX_QUEUE) size - (MAX_QUEUE - TRIM_SLACK) else 0

    /** The newest [MAX_QUEUE] of [lines], for a queue file read back on start. */
    fun <T> newest(lines: List<T>): List<T> = if (lines.size > MAX_QUEUE) lines.takeLast(MAX_QUEUE) else lines

    /** A batch for `POST /api/events`: `{"events":[...]}` from lines that are each one encoded event. */
    fun batchBody(lines: List<String>): String = lines.joinToString(",", prefix = "{\"events\":[", postfix = "]}")

    /** Whether a line read back from the queue file is a whole event, as a write cut short by a kill may leave one that is not. */
    fun isEvent(line: String): Boolean = runCatching {
        val obj = Json.parseToJsonElement(line) as? JsonObject ?: return false
        (obj["name"] as? JsonPrimitive)?.isString == true && obj["props"] is JsonObject
    }.getOrDefault(false)

    /** What a batch's answer means for its events. */
    enum class Outcome {
        /** Taken: the events leave the queue. */
        Sent,

        /** Refused for what it is, so sending it again would be refused again: the events leave the queue too. */
        Refused,

        /** Not taken now (no network, the server away or busy, Access in the way): the events stay and go later. */
        Later,
    }

    fun outcome(status: Int): Outcome = when (status) {
        in 200..299 -> Outcome.Sent
        400, 413, 422 -> Outcome.Refused
        else -> Outcome.Later
    }

    /**
     * The session to use when the app comes to the front: [current] carries on unless there is none or the app was in
     * the background for [SESSION_GAP_MS] or more since [backgroundAt]; then [fresh] makes a new one.
     */
    fun session(current: String?, backgroundAt: Long?, now: Long, fresh: () -> String): String =
        if (current == null || !validId(current) || (backgroundAt != null && now - backgroundAt >= SESSION_GAP_MS)) fresh() else current

    /**
     * How the app opened, for `app.open`: `cold` when this activity is the process's first and came within a few
     * seconds of the process starting (not a process WorkManager woke earlier), timed from the process start; `warm`
     * otherwise, timed from the activity's creation. All times on the same monotonic clock, in milliseconds.
     */
    fun appOpen(processStart: Long, activityStart: Long, firstActivity: Boolean, now: Long): Pair<String, Long> =
        if (firstActivity && activityStart - processStart < COLD_WINDOW_MS) "cold" to (now - processStart).coerceAtLeast(0)
        else "warm" to (now - activityStart).coerceAtLeast(0)

    private const val COLD_WINDOW_MS = 5_000L

    /** A file's kind for `file.view`: its extension in lower case, or `none`. Never the name or the path. */
    fun fileKind(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return "none"
        val ext = name.substring(dot + 1).lowercase()
        return if (ext.length <= 10 && ext.all { it in 'a'..'z' || it in '0'..'9' }) ext else "other"
    }

    /** The top [CRASH_FRAMES] frames of [error], one per line, as `class.method(File.kt:12)`. */
    fun stackTop(error: Throwable): String = error.stackTrace.take(CRASH_FRAMES).joinToString("\n") { it.toString() }

    /** The props of a `crash` event. */
    fun crashProps(error: Throwable, screen: String): Map<String, Any?> = mapOf(
        "kind" to (error::class.java.simpleName.ifBlank { "Throwable" }),
        "message" to error.message.orEmpty(),
        "stack" to stackTop(error),
        "screen" to screen,
    )
}

/**
 * Time on each screen, for `screen.view` and `screen.time`. A screen's time runs from when it shows until another
 * shows or the app goes to the background, and starts again when it comes back. Times are monotonic milliseconds;
 * each call returns the events it makes, as a name and its props.
 */
class ScreenClock {
    var current: String? = null
        private set
    private var since: Long? = null

    /** [screen] is now on top. Nothing happens when it already was. */
    fun show(screen: String, now: Long): List<Pair<String, Map<String, Any?>>> {
        if (screen == current) {
            if (since == null) since = now
            return emptyList()
        }
        val previous = current
        val events = timeOf(now).toMutableList()
        current = screen
        since = now
        events += "screen.view" to mapOf("screen" to screen, "from" to (previous ?: "start"))
        return events
    }

    /** The app went to the background: the screen's time so far is counted. */
    fun pause(now: Long): List<Pair<String, Map<String, Any?>>> = timeOf(now).also { since = null }

    /** The app is in front again, on the same screen. */
    fun resume(now: Long) {
        if (current != null && since == null) since = now
    }

    private fun timeOf(now: Long): List<Pair<String, Map<String, Any?>>> {
        val screen = current ?: return emptyList()
        val start = since ?: return emptyList()
        val seconds = (now - start) / 1000.0
        if (seconds < 0.5) return emptyList()
        return listOf("screen.time" to mapOf("screen" to screen, "seconds" to Math.round(seconds * 10) / 10.0))
    }
}

class PrivateUsageClock(private var available: Boolean = false) {
    private val screens = ScreenClock()
    var inForeground = false
        private set
    private var since: Long? = null
    private var elapsedMs = 0L

    fun show(screen: String, now: Long): List<Pair<String, Map<String, Any?>>> =
        if (available) screens.show(screen, now) else emptyList()

    fun foreground(now: Long) {
        inForeground = true
        elapsedMs = 0
        since = if (available) now else null
        if (available) screens.resume(now)
    }

    fun setPrivateConnection(available: Boolean, now: Long): List<Pair<String, Map<String, Any?>>> {
        if (this.available == available) return emptyList()
        val events = if (available) emptyList() else screens.pause(now)
        since?.let { elapsedMs += now - it }
        this.available = available
        since = if (available && inForeground) now else null
        if (available && inForeground) screens.resume(now)
        return events
    }

    fun background(now: Long): List<Pair<String, Map<String, Any?>>> {
        val events = if (available) screens.pause(now) else emptyList()
        val seconds = (elapsedMs + (since?.let { now - it } ?: 0L)) / 1000
        val background = if (available && inForeground) listOf("app.background" to mapOf<String, Any?>("seconds" to seconds)) else emptyList()
        inForeground = false
        since = null
        elapsedMs = 0
        return events + background
    }
}
