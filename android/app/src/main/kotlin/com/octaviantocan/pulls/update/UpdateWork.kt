package com.octaviantocan.pulls.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.octaviantocan.pulls.MainActivity
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.UpdateManifest
import com.octaviantocan.pulls.core.changesSince
import com.octaviantocan.pulls.core.plainChangelogText
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.ServerStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The background check for a new version. There is no push service: the phone asks the server's
 * `/android/latest.json` every quarter of an hour while it has a network (Android may stretch that when the
 * phone sleeps), and notifies once per version.
 */
object UpdateChecks {
    private const val WORK = "update-check"

    /** Starts the periodic check when the Settings switch is on and stops it when off. Safe to call on every start. */
    fun sync(context: Context) {
        runCatching {
            val work = WorkManager.getInstance(context)
            if (!AppSettings.get(context).updateNotifications) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: an app start does not push the next check back.
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w("pulls", "update check not scheduled", it) }
    }
}

/** One background check: notify about a newer build the phone has not been told about yet. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!AppSettings.get(context).updateNotifications || !UpdateNotice.allowed(context)) return Result.success()
        val manifest = withContext(Dispatchers.IO) {
            runCatching {
                val config = ServerStore.load(context)
                AppUpdater(context).check(config.url, config.headers(), AppVersion.code(context))
            }.onFailure { Log.w("pulls", "background update check failed", it) }.getOrNull()
        } ?: return Result.success()
        // The next start of the app asks for this build from its first frame.
        UpdatePrefs.saveOffer(context, manifest)
        if (manifest.versionCode <= UpdatePrefs.notifiedCode(context)) return Result.success()
        UpdateNotice.post(context, manifest)
        UpdatePrefs.markNotified(context, manifest.versionCode)
        return Result.success()
    }
}

/**
 * The "Pulls 0.17.0 is ready" notification. A tap opens Pulls where it was; Update opens it and starts the
 * download at once. Neither restarts the screen the reader was on (see [MainActivity]).
 */
object UpdateNotice {
    private const val CHANNEL = "updates"
    private const val ID = 1

    /** The extra on the intents this notification opens Pulls with: [OPEN] or [UPDATE]. */
    const val EXTRA = "com.octaviantocan.pulls.update"
    const val OPEN = "open"
    const val UPDATE = "update"

    /**
     * Android lets Pulls notify and the App updates channel is not turned off, so a version marked as notified was
     * really shown. A channel not made yet counts as on.
     */
    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun post(context: Context, manifest: UpdateManifest) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A new version of Pulls is ready to install."
        })
        val changes = manifest.changesSince(AppVersion.name(context))
        val summary = plainChangelogText(changes.firstOrNull()?.summary.orEmpty()).ifBlank { "Tap to see what's new." }
        // One version: its summary and what it lists. Several: each one's summary, newest first.
        val detail = if (changes.size == 1) {
            (listOf(summary) + changes[0].items.map { "• ${plainChangelogText(it)}" }).joinToString("\n")
        } else {
            changes.joinToString("\n") { "${it.version}: ${plainChangelogText(it.summary)}" }
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulls)
            .setColor(context.getColor(R.color.pulls_primary))
            .setContentTitle("Pulls ${manifest.versionName} is ready")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(intent(context, OPEN, 0))
            .addAction(Notification.Action.Builder(null, "Update", intent(context, UPDATE, 1)).build())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(ID, notification)
    }

    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)

    /**
     * Handles an intent this notification opened Pulls with, and says whether it was one. Update starts the
     * download straight away; the floating card on Home shows how far it got.
     */
    fun handle(context: Context, intent: Intent?): Boolean {
        val what = intent?.getStringExtra(EXTRA) ?: return false
        // Handled once: the same intent comes back if the activity is recreated.
        intent.removeExtra(EXTRA)
        clear(context)
        if (what == UPDATE) Updates.start(context)
        return true
    }

    private fun intent(context: Context, what: String, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, MainActivity::class.java).putExtra(EXTRA, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
