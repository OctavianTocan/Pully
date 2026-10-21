package com.octaviantocan.pulls.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone's usage analytics, sent only to our own server (root `CONTRACTS`, analytics-stays-on-our-server). [track]
 * puts an event in memory and appends it to `filesDir/analytics/queue.ndjson`, so it survives no network and a kill.
 * While the app is in front the queue goes up every 30 seconds and when it leaves the front; what is left goes with a
 * WorkManager job that waits for a network. The Usage stats switch in Settings stops it and drops the queue.
 *
 * Until [install] runs (and in JVM tests) every call does nothing.
 */
object Analytics {
    private const val PREFS = "pulls-analytics"
    private const val WORK = "analytics-flush"
    private const val TAG = "pulls"

    @Volatile private var app: Context? = null
    @Volatile private var enabled = false
    @Volatile private var privateConnection = false
    @Volatile private var connectionRevision = 0L
    @Volatile private var version = ""
    @Volatile private var device = ""
    @Volatile private var session = ""

    /** The screen on top, by its fixed name: what a crash or an error says it happened on. */
    @Volatile var screen: String = "start"
        private set

    /** The queue in memory, oldest first; the queue file holds the same lines. Guarded by [lock]. */
    private val queue = ArrayDeque<Queued>()
    private val lock = Any()
    private var seq = 0L

    /** Every write of the queue file holds this, the crash handler's too. */
    private val fileLock = Any()
    @Volatile private var loaded = false
    private val ready = CompletableDeferred<Unit>()

    /** File work, one at a time and in order, off the main thread. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "pulls-analytics").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private var ticker: Job? = null

    private val screens = ScreenClock()
    @Volatile private var processStart = 0L
    @Volatile private var activityStart = 0L
    @Volatile private var activities = 0
    @Volatile private var opened = false
    @Volatile private var frontSince: Long? = null

    private class Queued(val seq: Long, val line: String)

    /** Called from `PullsApplication.onCreate`: notes when the process started, reads the queue and sets the crash handler. */
    fun install(context: Context, on: Boolean, privateConnection: Boolean = true) {
        processStart = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        app = appContext
        version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty() }.getOrDefault("")
        val prefs = prefs(appContext)
        device = prefs.getString("device", null)?.takeIf(AnalyticsRules::validId) ?: newId().also { prefs.edit().putString("device", it).apply() }
        session = prefs.getString("session", null)?.takeIf(AnalyticsRules::validId) ?: newId().also { prefs.edit().putString("session", it).apply() }
        enabled = on
        this.privateConnection = privateConnection
        io.execute { load() }
        installCrashHandler()
    }

    /**
     * Records [name] with [props]. Props are cleaned first ([AnalyticsRules.cleanProps]): only counts, timings, enums
     * and on or off values belong here, never what was read or typed (analytics-what-not-how-much).
     */
    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled || !privateConnection || app == null) return
        val line = event(name, props).encode()
        val dropped: Int
        synchronized(lock) {
            queue.addLast(Queued(++seq, line))
            dropped = AnalyticsRules.overflow(queue.size)
            repeat(dropped) { queue.removeFirst() }
        }
        io.execute { if (loaded) { if (dropped > 0) rewrite() else append(line) } }
    }

    fun track(name: String, vararg props: Pair<String, Any?>) = track(name, mapOf(*props))

    /** A caught failure the reader was told about (a page that did not load), as `error.client`. */
    fun error(kind: String, message: String?) = track("error.client", "kind" to kind, "message" to message.orEmpty().take(120), "screen" to screen)

    /** A server call, for `rpc.call`: the RPC's name only, never its arguments. */
    fun rpc(method: String, ms: Long, ok: Boolean, status: Int?) =
        track("rpc.call", "method" to method, "ms" to ms, "ok" to ok, "status" to status)

    // --- Screens and the app's life ---

    /** [name] is the screen on top now: one of a fixed set of lowercase words, never a title or a repository. */
    fun showScreen(name: String) {
        screen = name
        emit(screens.show(name, SystemClock.elapsedRealtime()))
    }

    /** `MainActivity.onCreate`: the start of a launch, for `app.open`. One [restored] after a rotation is not a launch. */
    fun activityCreated(restored: Boolean) {
        activityStart = SystemClock.elapsedRealtime()
        activities += 1
        opened = restored
    }

    /** The first frame of Home after a launch: sends `app.open` once for the activity. */
    fun firstFrame() {
        if (opened || activityStart == 0L) return
        opened = true
        val (start, ms) = AnalyticsRules.appOpen(processStart, activityStart, activities == 1, SystemClock.elapsedRealtime())
        track("app.open", "start" to start, "ms" to ms)
    }

    /** `MainActivity.onStart`: a new session after 30 minutes away, the screen's time running again, and sending every 30 seconds. */
    fun foreground() {
        val context = app ?: return
        val now = SystemClock.elapsedRealtime()
        frontSince = now
        val prefs = prefs(context)
        val away = prefs.getLong("backgroundAt", -1L).takeIf { it >= 0 }
        val wall = System.currentTimeMillis()
        val next = AnalyticsRules.session(session, away, wall, ::newId)
        if (next != session) {
            session = next
            prefs.edit().putString("session", next).apply()
        }
        prefs.edit().remove("backgroundAt").apply()
        screens.resume(now)
        startTicker()
    }

    /** `MainActivity.onStop` (not for a rotation): `app.background`, the screen's time, a send now and a job for what is left. */
    fun background() {
        val context = app ?: return
        val now = SystemClock.elapsedRealtime()
        emit(screens.pause(now))
        frontSince?.let { track("app.background", "seconds" to (now - it) / 1000) }
        frontSince = null
        prefs(context).edit().putLong("backgroundAt", System.currentTimeMillis()).apply()
        ticker?.cancel()
        ticker = null
        if (!enabled || !privateConnection) return
        scope.launch { flush() }
        scheduleRetry(context)
    }

    // --- The Usage stats switch ---

    /** Turns tracking on or off. Off drops the queue, in memory and on disk, and sends nothing more (analytics-can-be-turned-off). */
    fun setEnabled(on: Boolean) {
        val context = app ?: return
        enabled = on
        if (on) {
            if (frontSince != null) startTicker()
            return
        }
        ticker?.cancel()
        ticker = null
        synchronized(lock) { queue.clear() }
        io.execute { synchronized(fileLock) { queueFile(context).delete() } }
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    /** Guest mode pauses private telemetry without changing consent or discarding a returning account's queue. */
    fun setPrivateConnection(available: Boolean) {
        if (privateConnection == available) return
        connectionRevision++
        privateConnection = available
        ticker?.cancel()
        ticker = null
        val context = app ?: return
        if (available) {
            if (frontSince != null) startTicker()
        } else runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    // --- Sending ---

    /**
     * Sends the queue in batches of [AnalyticsRules.BATCH] to `POST /api/events` on the server set in Settings, with the
     * same Access headers as `/api/rpc`. True when nothing is left to send.
     */
    suspend fun flush(): Boolean {
        val context = app ?: return true
        if (!enabled || !privateConnection) return true
        val opening = connectionRevision
        ready.await()
        return sending.withLock {
            withContext(Dispatchers.IO) {
                while (enabled && privateConnection && connectionRevision == opening) {
                    val batch = synchronized(lock) { queue.take(AnalyticsRules.BATCH).toList() }
                    if (batch.isEmpty()) return@withContext true
                    val status = runCatching {
                        val config = ServerStore.load(context)
                        PullsApi(config.url, config.headers()).postEvents(AnalyticsRules.batchBody(batch.map { it.line }))
                    }.getOrElse { return@withContext false }
                    if (AnalyticsRules.outcome(status) == AnalyticsRules.Outcome.Later) return@withContext false
                    val sent = batch.mapTo(HashSet()) { it.seq }
                    synchronized(lock) { queue.removeAll { it.seq in sent } }
                    io.execute { rewrite() }
                }
                true
            }
        }
    }

    /** A one-off job that sends what is left once there is a network, retrying with a back-off until it goes. */
    private fun scheduleRetry(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<AnalyticsFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "analytics job not scheduled", it) }
    }

    private fun startTicker() {
        if (!enabled || !privateConnection || ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                flush()
                delay(AnalyticsRules.FLUSH_EVERY_MS)
            }
        }
    }

    // --- The queue file ---

    private fun queueFile(context: Context) = File(File(context.filesDir, "analytics"), "queue.ndjson")

    /** Reads what an earlier run left (a crash's event among it) in front of anything tracked since the start. */
    private fun load() {
        val context = app ?: return
        try {
            val file = queueFile(context)
            val kept = synchronized(fileLock) {
                if (!file.exists()) emptyList() else AnalyticsRules.newest(file.readLines().filter(AnalyticsRules::isEvent))
            }
            synchronized(lock) {
                val since = queue.toList()
                queue.clear()
                kept.forEach { queue.addLast(Queued(++seq, it)) }
                since.forEach { queue.addLast(Queued(++seq, it.line)) }
                repeat(AnalyticsRules.overflow(queue.size)) { queue.removeFirst() }
            }
            loaded = true
            if (!enabled) synchronized(fileLock) { file.delete() } else rewrite()
        } catch (e: Exception) {
            Log.w(TAG, "analytics queue unreadable", e)
            loaded = true
        } finally {
            ready.complete(Unit)
        }
    }

    private fun append(line: String) {
        val context = app ?: return
        runCatching {
            synchronized(fileLock) {
                val file = queueFile(context)
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
        }.onFailure { Log.w(TAG, "analytics append failed", it) }
    }

    /** Writes the file again from memory, through a temporary file so a kill never leaves half of it. */
    private fun rewrite() {
        val context = app ?: return
        val lines = synchronized(lock) { queue.map { it.line } }
        runCatching {
            synchronized(fileLock) {
                val file = queueFile(context)
                if (!enabled || lines.isEmpty()) { file.delete(); return@runCatching }
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "queue.ndjson.tmp")
                temp.writeText(lines.joinToString("\n", postfix = "\n"))
                if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
            }
        }.onFailure { Log.w(TAG, "analytics rewrite failed", it) }
    }

    // --- Crashes ---

    /**
     * Writes a `crash` event straight to the queue file on the crashing thread, then hands over to the handler that
     * was there before. It goes up on the next launch.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { recordCrash(error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recordCrash(error: Throwable) {
        val context = app ?: return
        if (!enabled || !privateConnection) return
        val line = event("crash", AnalyticsRules.crashProps(error, screen)).encode()
        synchronized(fileLock) {
            val file = queueFile(context)
            file.parentFile?.mkdirs()
            if (loaded) {
                // Everything in memory goes down with it, as some of it may not have reached the file yet.
                val lines = synchronized(lock) { queue.map { it.line } } + line
                file.writeText(AnalyticsRules.newest(lines).joinToString("\n", postfix = "\n"))
            } else {
                file.appendText(line + "\n")
            }
        }
    }

    // --- Helpers ---

    private fun emit(events: List<Pair<String, Map<String, Any?>>>) = events.forEach { (name, props) -> track(name, props) }

    private fun event(name: String, props: Map<String, Any?>) = AnalyticsEvent(
        name = name,
        at = Instant.now().toString(),
        version = version,
        session = session,
        device = device,
        props = AnalyticsRules.cleanProps(props),
    )

    private fun newId(): String = UUID.randomUUID().toString()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Sends what the queue still holds once the phone has a network; retried with a back-off until it is all gone. */
class AnalyticsFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (Analytics.flush()) Result.success() else Result.retry()
}
