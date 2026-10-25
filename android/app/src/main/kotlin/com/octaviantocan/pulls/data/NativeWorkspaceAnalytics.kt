package com.octaviantocan.pulls.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** The shared bounded queue, in an immutable-account directory separate from legacy telemetry. */
class NativeWorkspaceAnalytics(
    context: Context,
    private val workspace: PersonalWorkspace,
    namespace: String,
    private val admitted: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private val clock = PrivateUsageClock()
    private val prefs = context.getSharedPreferences("pully-workspace-usage-$namespace", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "analytics-workspace/$namespace")
    private val file = File(directory, "queue.ndjson")
    private val queue = UsageAnalyticsQueue(
        object : AnalyticsDisk {
            override fun read(): List<String> = if (file.isFile) file.readLines() else emptyList()
            override fun replace(lines: List<String>) {
                directory.mkdirs()
                val temporary = File(directory, "queue.tmp")
                temporary.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
                check(temporary.renameTo(file))
            }
            override fun metadata(name: String): String? = prefs.getString(name, null)
            override fun metadata(name: String, value: String?) { prefs.edit().putString(name, value).apply() }
        }, AnalyticsIo { io.execute(it) }, "android",
        com.octaviantocan.pulls.update.AppVersion.name(context), { Instant.now().toString() },
        { UUID.randomUUID().toString() }, { !closed && admitted() }, workspace::postEvents,
        AppSettings.get(context).usageStats,
    )

    init {
        clock.setPrivateConnection(true, SystemClock.elapsedRealtime())
        foreground()
        scope.launch {
            while (isActive) {
                queue.flush()
                delay(30_000)
            }
        }
    }

    fun track(name: String, props: Map<String, Any?>) = queue.track(name, props)
    fun showScreen(name: String) = emit(clock.show(name, SystemClock.elapsedRealtime()))
    fun foreground() { clock.foreground(SystemClock.elapsedRealtime()); queue.foreground(System.currentTimeMillis()) }
    fun background() { emit(clock.background(SystemClock.elapsedRealtime())); queue.background(System.currentTimeMillis()) }
    fun setEnabled(enabled: Boolean) = queue.setEnabled(enabled)
    suspend fun flush(): Boolean = queue.flush()
    fun close() {
        if (closed) return
        background()
        closed = true
        queue.close()
        scope.cancel()
    }
    private fun emit(events: List<Pair<String, Map<String, Any?>>>) { events.forEach { (name, props) -> queue.track(name, props) } }
    companion object { private val io = Executors.newSingleThreadExecutor() }
}
