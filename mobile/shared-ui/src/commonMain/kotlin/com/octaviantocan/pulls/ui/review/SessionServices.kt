package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.Tour
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Native persistence owns disk I/O; the session keeps the existing cached-first tour policy. */
interface SessionTourCache {
    fun get(pull: String): Tour?
    suspend fun put(pull: String, tour: JsonObject)
}

interface SessionClock {
    fun monotonicMillis(): Long
    fun wallMillis(): Long
}

object SystemSessionClock : SessionClock {
    private val start = TimeSource.Monotonic.markNow()
    override fun monotonicMillis(): Long = start.elapsedNow().inWholeMilliseconds
    override fun wallMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

/** Opaque native notification ownership captured before a request, never after its response. */
interface SessionWorkSource

enum class SessionCue { Error, Ready }

/** Native adapters preserve first-party analytics, sound settings and notification authorization. */
interface SessionEffects {
    fun track(name: String, vararg props: Pair<String, Any?>) {}
    fun fileShown(path: String, lines: Int, layout: String) {}
    fun error(name: String, message: String?) {}
    fun workSource(): SessionWorkSource? = null
    fun started(task: TaskState, source: SessionWorkSource?) {}
    fun updated(task: TaskState, source: SessionWorkSource?) {}
    fun cue(cue: SessionCue) {}
}

object NoSessionEffects : SessionEffects

/** A typed, session-owned slot for a screen slice not yet extracted; it never uses a global weak map. */
class SessionStateKey<T : Any>
