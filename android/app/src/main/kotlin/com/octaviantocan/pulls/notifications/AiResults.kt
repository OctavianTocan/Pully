package com.octaviantocan.pulls.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.octaviantocan.pulls.MainActivity
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.AiResultSnapshot
import com.octaviantocan.pulls.core.FollowedAiResult
import com.octaviantocan.pulls.core.aiResultOutcomes
import com.octaviantocan.pulls.core.followAiResult
import com.octaviantocan.pulls.core.parsePullKey
import com.octaviantocan.pulls.core.retainedAiResults
import com.octaviantocan.pulls.core.sha256Hex
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.NativeEntry
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.TaskState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Durable outcome alerts for accepted work explicitly requested on this device, never shared task history. */
object AiResults {
    const val CHANNEL = "ai-results"
    private const val WORK = "ai-result-check"
    private const val PREFS = "ai-result-notices"
    private const val EXTRA_SOURCE = "com.octaviantocan.pulls.ai-result-source"
    private const val EXTRA_KIND = "com.octaviantocan.pulls.ai-result-kind"
    private var generation = 0L

    /** Captured before a start/request; opaque and never includes credentials in its representation. */
    class Source internal constructor(internal val id: String, internal val generation: Long)
    data class Destination(val url: String, val kind: String)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun enabled(context: Context): Boolean {
        val kept = prefs(context)
        return kept.getBoolean("enabled", false) && kept.getString("scope", null) == identity(context)
    }

    fun allowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).let {
        it.areNotificationsEnabled() && it.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        if (!enabled) { reset(context); return }
        val identity = identity(context) ?: return
        if (prefs(context).getString("scope", null) != identity || !prefs(context).getBoolean("enabled", false)) {
            cancelNotices(context)
            prefs(context).edit().clear().putString("scope", identity).putBoolean("enabled", true).commit()
        }
        channel(context)
        sync(context)
    }

    /** Called on startup/resume; a denied channel clears pending ownership rather than replaying old results later. */
    @Synchronized fun sync(context: Context) {
        if (!NativeEntry.privateConnection(context)) {
            generation++
            cancelNotices(context)
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
            return
        }
        if (!enabled(context)) { reset(context); return }
        if (!allowed(context)) clearFollowed(context)
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AiResultWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }

    /** Explicit disable and identity reset remove owned work and outstanding notices before another identity is used. */
    @Synchronized fun reset(context: Context) {
        clearFollowed(context)
        prefs(context).edit().clear().commit()
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    @Synchronized fun source(context: Context): Source? {
        if (!NativeEntry.privateConnection(context)) return null
        if (!enabled(context)) return null
        if (!allowed(context)) { clearFollowed(context); return null }
        return identity(context)?.let { Source(it, generation) }
    }

    /** The caller has received a successful server task ID for an explicit user request. No list-all adoption. */
    @Synchronized fun started(context: Context, task: TaskState, source: Source?) {
        if (!current(context, source) || !allowed(context)) return
        val route = route(task.key, ServerStore.load(context).url) ?: return
        val followed = followAiResult(task.snapshot(), System.currentTimeMillis(), route) ?: return
        val kept = read(context)
        // Repeated acceptance of a joined task never reopens an already consumed outcome.
        if (kept.any { it.id == followed.id }) return
        write(context, kept + followed)
    }

    /** Only successful server responses reach this method; failed requests leave ownership unchanged. */
    @Synchronized fun observed(context: Context, tasks: List<TaskState>, source: Source?, silent: Boolean = false) {
        if (!current(context, source)) return
        var kept = read(context)
        for (outcome in aiResultOutcomes(kept, tasks.map { it.snapshot() })) {
            if (!current(context, source)) return
            val consume = !outcome.alerts || !allowed(context) || post(context, outcome.followed, outcome.status, source!!, silent)
            if (consume) {
                kept = kept.map { if (it.id == outcome.followed.id) it.copy(settled = true) else it }
                write(context, kept)
            }
        }
    }

    suspend fun check(context: Context): Boolean {
        Account.load(context)
        sync(context)
        val source = source(context) ?: return true
        if (synchronized(this) { read(context).none { !it.settled } }) return true
        val tasks = try {
            withTimeoutOrNull(30_000) { Repo(context).allTasks() } ?: return false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { return false }
        observed(context, tasks, source)
        return true
    }

    fun openSettings(context: Context) {
        channel(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isNotification(intent: Intent?): Boolean = intent?.hasExtra(EXTRA_SOURCE) == true || intent?.hasExtra(EXTRA_KIND) == true

    /** Validate our source and sanitized route again when a stale or forged notification intent is opened. */
    @Synchronized fun destination(context: Context, intent: Intent?): Destination? {
        if (!NativeEntry.privateConnection(context) || intent == null || intent.getStringExtra(EXTRA_SOURCE) != identity(context) || !enabled(context)) return null
        val url = intent.dataString ?: return null
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return null
        val followed = read(context).firstOrNull { it.route == url && it.kind == kind } ?: return null
        if (route(followed.key, ServerStore.load(context).url) != url) return null
        return Destination(url, kind)
    }

    private fun identity(context: Context): String? {
        Account.load(context)
        if (Account.mustSignIn) return null
        val config = ServerStore.load(context)
        val headers = config.headers() + Account.headers(context)
        return sha256Hex((config.url.trimEnd('/') + "\n" + Account.email.orEmpty() + "\n" +
            headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }).toByteArray())
    }

    private fun current(context: Context, source: Source?): Boolean =
        source != null && NativeEntry.privateConnection(context) && source.generation == generation && enabled(context) && identity(context) == source.id

    private fun read(context: Context): List<FollowedAiResult> = runCatching {
        val array = Json.parseToJsonElement(prefs(context).getString("followed", "[]")!!) as JsonArray
        array.mapNotNull { raw ->
            val o = raw as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            FollowedAiResult(text("id"), text("key"), text("kind"),
                (o["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                text("route"), (o["settled"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }.getOrDefault(emptyList()).let { retainedAiResults(it, System.currentTimeMillis()) }

    private fun write(context: Context, followed: List<FollowedAiResult>) {
        val bounded = retainedAiResults(followed, System.currentTimeMillis())
        val raw = JsonArray(bounded.map { task -> buildJsonObject {
            put("id", task.id); put("key", task.key); put("kind", task.kind); put("at", task.followedAt)
            put("route", task.route); put("settled", task.settled)
        } })
        prefs(context).edit().putString("followed", raw.toString()).commit()
    }

    private fun clearFollowed(context: Context) {
        generation++
        cancelNotices(context)
        prefs(context).edit().remove("followed").commit()
    }

    private fun cancelNotices(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun channel(context: Context) = context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL, "AI results", NotificationManager.IMPORTANCE_DEFAULT))

    private fun post(context: Context, task: FollowedAiResult, status: String, source: Source, silent: Boolean): Boolean {
        if (!current(context, source)) return false
        channel(context)
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(task.route))
            .putExtra(EXTRA_SOURCE, source.id).putExtra(EXTRA_KIND, task.kind)
            .addCategory("com.octaviantocan.pulls.ai-result.${sha256Hex((source.id + task.id).toByteArray())}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Intent identity includes a per-task request code; UPDATE_CURRENT never redirects another task's tap.
        val open = PendingIntent.getActivity(context, task.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val name = when (task.kind) { "tour" -> "Tour"; "brief" -> "Brief"; "critique" -> "AI review"; else -> "Analysis" }
        val notice = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pulls)
            .setContentTitle("$name ${if (status == "failed") "failed" else "finished"}")
            .setContentText("Open Pully to see the result.").setContentIntent(open).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setSilent(silent).build()
        return try {
            if (!current(context, source) || !allowed(context)) return false
            context.getSystemService(NotificationManager::class.java).notify(tag(task), 0, notice)
            true
        } catch (_: SecurityException) { false }
    }

    private fun tag(task: FollowedAiResult) = "ai-result:${task.id}"

    private fun TaskState.snapshot() = AiResultSnapshot(id, key, kind, status)

    private fun route(key: String, server: String): String? {
        parsePullKey(key)?.let {
            val segment = Regex("[A-Za-z0-9_.-]+")
            if (segment.matches(it.owner) && segment.matches(it.repo)) return "https://github.com/${it.owner}/${it.repo}/pull/${it.number}"
        }
        val posted = key.removePrefix("posted:").takeIf { key.startsWith("posted:") && Regex("[A-Za-z0-9_-]{1,128}").matches(it) }
        return posted?.let { "${server.trimEnd('/')}/-/$it" }
    }
}

class AiResultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (AiResults.check(applicationContext) || runAttemptCount >= 2) Result.success() else Result.retry()
}
