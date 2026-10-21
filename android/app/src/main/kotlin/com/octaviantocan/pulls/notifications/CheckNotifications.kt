package com.octaviantocan.pulls.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.octaviantocan.pulls.MainActivity
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.CheckNotificationState
import com.octaviantocan.pulls.core.CheckNotificationSource as CheckSource
import com.octaviantocan.pulls.core.CheckSnapshot
import com.octaviantocan.pulls.core.ObservedCheck
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.checkNotificationUrl
import com.octaviantocan.pulls.core.delivered
import com.octaviantocan.pulls.core.evaluateChecks
import com.octaviantocan.pulls.core.sha256Hex
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.NativeEntry
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class WatchedPull(
    val owner: String, val repo: String, val number: Int,
    val revision: String = UUID.randomUUID().toString(),
    val state: CheckNotificationState = CheckNotificationState(),
) {
    val key get() = "$owner/$repo#$number"
    val target get() = OpenTarget.Pull(owner, repo, number)
}

/** Explicit device-local subscriptions. WorkManager checks are delayed, not remote push delivery. */
object CheckNotifications {
    const val CHANNEL = "pr-checks"
    private const val WORK = "pr-check-notifications"
    private const val IMMEDIATE = "pr-check-baseline"
    private const val TAG = "pr-checks:"
    private const val SOURCE_EXTRA = "com.octaviantocan.pulls.check-source"
    private const val NOTICE_ID = 3
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val polling = Mutex()

    private fun prefs(context: Context) = context.getSharedPreferences("pully-check-notifications", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun allowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).let {
        it.areNotificationsEnabled() && it.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun openSettings(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "PR checks", NotificationManager.IMPORTANCE_DEFAULT))
        val action = if (manager.areNotificationsEnabled()) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS
        context.startActivity(Intent(action)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL))
    }

    private fun source(context: Context): CheckSource {
        Account.load(context)
        val config = ServerStore.load(context)
        val account = Account.email.orEmpty()
        val headers = (config.headers() + Account.headers(context)).toSortedMap()
        return CheckSource(
            sha256Hex("${config.url.trimEnd('/')}\u0000$account".toByteArray()),
            sha256Hex((config.url + "\u0000" + headers.entries.joinToString("\u0000") { "${it.key}=${it.value}" }).toByteArray()),
            prefs(context).getLong("epoch", 0),
        )
    }

    private fun watches(context: Context, source: CheckSource): List<WatchedPull> =
        runCatching { json.decodeFromString<List<WatchedPull>>(prefs(context).getString(source.key, "[]")!!) }.getOrDefault(emptyList())

    private fun save(context: Context, source: CheckSource, watches: List<WatchedPull>) {
        prefs(context).edit().putString(source.key, json.encodeToString(watches)).commit()
    }

    fun isWatching(context: Context, target: OpenTarget.Pull): Boolean = synchronized(lock) {
        watches(context, source(context)).any { it.owner == target.owner && it.repo == target.repo && it.number == target.number }
    }

    fun watchedCount(context: Context): Int = synchronized(lock) { watches(context, source(context)).size }

    /** The first authoritative poll silently baselines existing failures; a failed fetch stays uninitialized. */
    fun watch(context: Context, target: OpenTarget.Pull) {
        synchronized(lock) {
            val source = source(context)
            val existing = watches(context, source)
            if (existing.none { it.owner == target.owner && it.repo == target.repo && it.number == target.number }) {
                save(context, source, existing + WatchedPull(target.owner, target.repo, target.number))
            }
            prefs(context).edit().putBoolean("enabled", true).commit()
        }
        sync(context)
        if (allowed(context)) WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CheckNotificationWorker>().setConstraints(network()).build())
    }

    fun unwatch(context: Context, target: OpenTarget.Pull) {
        synchronized(lock) {
            val source = source(context)
            val key = "${target.owner}/${target.repo}#${target.number}"
            save(context, source, watches(context, source).filterNot { it.key == key })
            context.getSystemService(NotificationManager::class.java).cancel("$TAG${source.key}:$key", NOTICE_ID)
        }
        sync(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        synchronized(lock) {
            prefs(context).edit().putBoolean("enabled", on).commit()
            if (!on) resetBaseline(context)
        }
        sync(context)
    }

    /** Call before changing account/server credentials, including sign-out. Old in-flight work is invalidated. */
    fun clearForSourceChange(context: Context) {
        synchronized(lock) {
            resetBaseline(context)
            prefs(context).edit().putLong("epoch", prefs(context).getLong("epoch", 0) + 1).commit()
            cancelNotices(context)
        }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
    }

    private fun resetBaseline(context: Context) {
        val source = source(context)
        save(context, source, watches(context, source).map { it.copy(revision = UUID.randomUUID().toString(), state = CheckNotificationState()) })
    }

    /** Safe on every process start/resume. An explicit watch is still required. */
    fun sync(context: Context) {
        Account.load(context)
        val work = WorkManager.getInstance(context)
        if (!NativeEntry.privateConnection(context)) {
            synchronized(lock) {
                prefs(context).edit().putLong("epoch", prefs(context).getLong("epoch", 0) + 1).commit()
                cancelNotices(context)
            }
            work.cancelUniqueWork(WORK)
            work.cancelUniqueWork(IMMEDIATE)
            return
        }
        if (!enabled(context) || !allowed(context) || Account.mustSignIn || watchedCount(context) == 0) {
            work.cancelUniqueWork(WORK)
            work.cancelUniqueWork(IMMEDIATE)
            cancelNotices(context)
            if (!allowed(context)) synchronized(lock) { resetBaseline(context) }
            return
        }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckNotificationWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        // The same evaluator also reconciles on explicit enable/start/resume, without a process keepalive.
        work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CheckNotificationWorker>().setConstraints(network()).build())
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun cancelNotices(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith(TAG) == true }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** Reject an old-account/server notification tap before IncomingLinks opens its target. */
    fun isNotification(intent: Intent): Boolean = intent.hasExtra(SOURCE_EXTRA)

    fun destination(context: Context, intent: Intent): String? {
        val current = source(context) // Loads Account in a cold process before consulting mustSignIn.
        val expected = intent.getStringExtra(SOURCE_EXTRA) ?: return null
        return intent.dataString?.takeIf { NativeEntry.privateConnection(context) && !Account.mustSignIn && expected == current.key }
    }

    internal suspend fun poll(context: Context) = polling.withLock {
        Account.load(context)
        if (!NativeEntry.privateConnection(context) || !enabled(context) || Account.mustSignIn) return@withLock
        if (!allowed(context)) {
            synchronized(lock) { resetBaseline(context); cancelNotices(context) }
            return@withLock
        }
        val source = source(context)
        val subscriptions = synchronized(lock) { watches(context, source) }
        val began = SystemClock.elapsedRealtime()
        // Rotate the start, so a slow server cannot starve the same subscriptions every run.
        val start = prefs(context).getInt("next", 0).let { if (subscriptions.isEmpty()) 0 else it.mod(subscriptions.size) }
        for (offset in subscriptions.indices) {
            if (SystemClock.elapsedRealtime() - began > 120_000) break
            val index = (start + offset).mod(subscriptions.size)
            val watched = subscriptions[index]
            if (!NativeEntry.privateConnection(context) || source(context) != source || !enabled(context)) break
            val raw = try { Repo(context).overview(watched.target) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { continue }
            val overview = raw["overview"] as? JsonObject ?: continue
            val snapshot = overview.checkSnapshot() ?: continue
            synchronized(lock) {
                if (!NativeEntry.privateConnection(context) || Account.mustSignIn || !enabled(context)) return@synchronized
                val current = watches(context, source)
                val subscription = current.firstOrNull { it.key == watched.key && it.revision == watched.revision } ?: return@synchronized
                if (!source.accepts(source(context), watched.revision, subscription.revision)) return@synchronized
                if (overview.text("state") in setOf("closed", "merged")) {
                    save(context, source, current.filterNot { it.key == watched.key })
                    context.getSystemService(NotificationManager::class.java).cancel("$TAG${source.key}:${watched.key}", NOTICE_ID)
                    return@synchronized
                }
                if (!allowed(context)) return@synchronized
                val evaluation = evaluateChecks(subscription.state, snapshot)
                val events = evaluation.events
                val delivered = if (events.isEmpty()) false else post(context, source, watched, events)
                val next = if (delivered) evaluation.state.delivered(events) else evaluation.state
                // Same source and subscription revision are held until the atomic preference replacement.
                save(context, source, current.map { if (it.key == watched.key) it.copy(state = next) else it })
                prefs(context).edit().putInt("next", index + 1).commit()
            }
        }
    }

    private fun post(context: Context, source: CheckSource, watched: WatchedPull, events: List<com.octaviantocan.pulls.core.CheckFailureEvent>): Boolean {
        if (!NativeEntry.privateConnection(context) || !allowed(context) || source(context) != source) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "PR checks", NotificationManager.IMPORTANCE_DEFAULT))
        val url = checkNotificationUrl(watched.owner, watched.repo, watched.number, events.singleOrNull()?.check?.url)
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url), context, MainActivity::class.java)
            .putExtra(SOURCE_EXTRA, source.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Source+PR are part of both tag and request identity; concurrent alerts cannot steal each other's tap.
        // MIME type participates in PendingIntent identity on all supported versions (API26+),
        // keeping the source separate without adding unsupported query fields to the target URL.
        intent.setDataAndType(android.net.Uri.parse(url), "application/vnd.pully.check.${source.key}")
        val open = PendingIntent.getActivity(context, NOTICE_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (events.size == 1) "Check ${events.single().failure.words}" else "${events.size} checks need attention"
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pulls)
            .setContentTitle(title).setContentText("${watched.owner}/${watched.repo} #${watched.number}")
            .setStyle(Notification.BigTextStyle().bigText(events.joinToString("\n") { "${it.check.name}: ${it.failure.words}" }))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pulls).setContentTitle("PR checks need attention").build())
            .build()
        return try { manager.notify("$TAG${source.key}:${watched.key}", NOTICE_ID, notification); true }
        catch (_: SecurityException) { false }
    }
}

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun JsonObject.checkSnapshot(): CheckSnapshot? {
    val head = (this["head"] as? JsonObject)?.text("oid").orEmpty()
    val checks = this["checks"] as? JsonArray ?: return null
    val hidden = (this["checksSummary"] as? JsonObject)?.get("hidden")?.jsonPrimitive?.intOrNull ?: return null
    return CheckSnapshot(head, checks.mapNotNull { element ->
        val check = element as? JsonObject ?: return@mapNotNull null
        ObservedCheck(check.text("id"), check.text("name"), check.text("conclusion"), check.text("startedAt"), check.text("completedAt"), check.text("url"))
    }, complete = hidden == 0)
}

class CheckNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CheckNotifications.poll(applicationContext)
        return Result.success()
    }
}
