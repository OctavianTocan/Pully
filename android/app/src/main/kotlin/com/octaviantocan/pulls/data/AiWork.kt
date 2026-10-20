package com.octaviantocan.pulls.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.octaviantocan.pulls.MainActivity
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.WorkNoticeState
import com.octaviantocan.pulls.core.WORK_NOTICE_FRESH_MS
import com.octaviantocan.pulls.core.isNotifiedWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One quiet system notification for server-side AI work, independent of the page that started it.
 * This observes work; it does not keep the app alive. Background following stops after 30 minutes,
 * and every notice expires after 30 seconds without a confirmed snapshot, including process death.
 * Opening Pully reattaches. No service, worker, permission request, progress estimate or local timer.
 * Calls come from the main thread, as do ReviewSession and the app lifecycle.
 */
object AiWork {
    private const val CHANNEL = "ai-work"
    private const val ID = 2
    private const val BACKGROUND_MS = 30 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var foreground = false
    private var backgroundAt = 0L
    private var follower: Job? = null
    private var generation = 0
    private val state = WorkNoticeState<Pair<String, Map<String, String>>>()
    private var lastSource: WorkNoticeState.Source<Pair<String, Map<String, String>>>? = null
    /** Opaque request identity; it never prints server credentials. */
    class Source internal constructor(internal val token: WorkNoticeState.Source<Pair<String, Map<String, String>>>)

    /** Capture before making the server request, never after a possibly late response. */
    fun source(): Source? = context?.let { Source(capture(it)) }

    /** Rechecks permission, account and server on each return, and discovers jobs started elsewhere. */
    fun foreground(context: Context) {
        this.context = context.applicationContext
        foreground = true
        capture(context)
        restart()
    }

    fun background() {
        if (foreground) backgroundAt = SystemClock.elapsedRealtime()
        foreground = false
    }

    /** Only a server-accepted task is announced; rejected starts never create an ongoing notice. */
    fun started(task: TaskState, source: Source?) {
        if (!task.running || !isNotifiedWork(task.kind)) return
        val context = context ?: return
        if (!state.activity(task.asWork(), source?.token, identity(context))) return
        // Fetch the server's own timestamp; the page's accepted-start timestamp is optimistic.
        restart()
    }

    /** A server snapshot can clear a completed, failed or cancelled task before the next global poll. */
    fun updated(task: TaskState, source: Source?) {
        val context = context ?: return
        if (!state.activity(task.asWork(), source?.token, identity(context))) return
        // Only complete global snapshots renew freshness. Another page's streaming task cannot
        // accidentally keep an unreachable task's stale notification alive.
        if (!task.running || follower?.isActive != true) restart()
    }

    /** Sign-out and a blocked account remove details and stop following immediately. */
    fun clear(context: Context) {
        generation++
        follower?.cancel()
        follower = null
        state.clear()
        lastSource = null
        cancel(context)
    }

    private fun restart() {
        val context = context ?: return
        val currentGeneration = ++generation
        follower?.cancel()
        follower = scope.launch {
            val repo = Repo(context)
            try {
                while (isActive) {
                    if (Account.mustSignIn || !allowed(context)) break
                    if (!foreground && SystemClock.elapsedRealtime() - backgroundAt >= BACKGROUND_MS) break
                    val requestSource = capture(context)
                    try {
                        val tasks = withTimeoutOrNull(15_000) { repo.allTasks() }
                        // A late reply from an account/server that was replaced must not become a notice.
                        if (Account.mustSignIn) continue
                        if (tasks != null) {
                            if (state.replace(tasks.map { it.asWork() }, requestSource, identity(context), System.currentTimeMillis())) post(context)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Do not renew a stale snapshot. Android expires its chronometer on its own.
                    }
                    val running = state.notice(System.currentTimeMillis()) != null
                    if (!foreground && !running) break
                    delay(if (running) 4_000 else 15_000)
                }
            } finally {
                if (generation == currentGeneration) cancel(context)
            }
        }
    }

    private fun identity(context: Context): Pair<String, Map<String, String>> =
        ServerStore.load(context).let { it.url to (it.headers() + Account.headers(context)) }

    private fun capture(context: Context): WorkNoticeState.Source<Pair<String, Map<String, String>>> {
        val source = state.capture(identity(context))
        if (lastSource !== source) cancel(context)
        lastSource = source
        return source
    }

    private fun TaskState.asWork() = WorkTask(key, kind, status, title, startedAt, id)

    private fun allowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).let {
        it.areNotificationsEnabled() && it.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun post(context: Context) {
        val notice = state.notice(System.currentTimeMillis())
        if (notice == null) {
            cancel(context)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI work", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Analysis and tour generation running on your server."
        })
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulls)
            .setColor(context.getColor(R.color.pulls_primary))
            .setContentTitle(notice.title)
            .setContentText(notice.lines.first())
            .setStyle(Notification.BigTextStyle().bigText(notice.lines.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(WORK_NOTICE_FRESH_MS)
            .setShowWhen(notice.startedAt != null)
            .setUsesChronometer(notice.startedAt != null)
            .apply { notice.startedAt?.let { setWhen(it) } }
            .build()
        try {
            manager.notify(ID, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked since the last poll.
            cancel(context)
        }
    }

    private fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}
