package com.octaviantocan.pulls.ui.review

import android.content.Context
import com.octaviantocan.pulls.PullsApplication
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.AiWork
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AnalyticsRules
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TourStore
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Sounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The application's explicit cache owner, shared by the Inbox warmer and review routes. */
fun reviewSessionCache(context: Context): ReviewSessionCache =
    (context.applicationContext as PullsApplication).reviewSessions

/** Native factory for the existing review route; its actual session and state live in common Kotlin. */
fun androidReviewSession(
    context: Context,
    target: OpenTarget,
    repo: Repo,
    scope: CoroutineScope,
    settings: AppSettings,
    tourStore: TourStore? = null,
    openedFrom: String = "other",
): ReviewSession = ReviewSession(
    target, repo, scope, settings,
    tourStore = tourStore?.let { AndroidTourCache(repo, it) },
    openedFrom = openedFrom,
    cache = reviewSessionCache(context),
    effects = AndroidSessionEffects,
    followServer = true,
)

private class AndroidTourCache(private val repo: Repo, private val store: TourStore) : SessionTourCache {
    private val source = runCatching { repo.source() }.getOrNull()
    override fun get(pull: String): Tour? =
        (if (source != null && repo.isCurrent(source)) store.get(pull) else null)?.takeIf { source != null && repo.isCurrent(source) }
    override suspend fun put(pull: String, tour: JsonObject) = withContext(Dispatchers.IO) {
        if (source != null && repo.isCurrent(source)) store.put(pull, tour)
    }
}

private class AndroidWorkSource(val source: AiWork.Source?) : SessionWorkSource

private object AndroidSessionEffects : SessionEffects {
    override fun track(name: String, vararg props: Pair<String, Any?>) = Analytics.track(name, *props)
    override fun error(name: String, message: String?) = Analytics.error(name, message)
    override fun fileShown(path: String, lines: Int, layout: String) = Analytics.track(
        "file.view", "kind" to AnalyticsRules.fileKind(path), "lines" to lines, "layout" to layout,
    )
    override fun workSource(): SessionWorkSource = AndroidWorkSource(AiWork.source())
    override fun started(task: TaskState, source: SessionWorkSource?) = AiWork.started(task, (source as? AndroidWorkSource)?.source)
    override fun updated(task: TaskState, source: SessionWorkSource?) = AiWork.updated(task, (source as? AndroidWorkSource)?.source)
    override fun cue(cue: SessionCue) = Sounds.play(if (cue == SessionCue.Ready) Cue.Ready else Cue.Error, Emphasis.Subtle)
}
