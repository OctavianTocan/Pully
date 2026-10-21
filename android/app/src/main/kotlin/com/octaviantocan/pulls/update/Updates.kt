package com.octaviantocan.pulls.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.ChangelogEntry
import com.octaviantocan.pulls.core.UpdateManifest
import com.octaviantocan.pulls.core.DigestMismatch
import com.octaviantocan.pulls.core.InstallRecovery
import com.octaviantocan.pulls.core.UpdateInstall
import com.octaviantocan.pulls.core.recoverUpdateInstall
import com.octaviantocan.pulls.core.changesSince
import com.octaviantocan.pulls.core.parseChangelog
import com.octaviantocan.pulls.core.parseUpdateManifest
import com.octaviantocan.pulls.core.since
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What an update is doing, for the floating card on Home and the What's new sheet. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus

    /** Android needs "Install unknown apps" for Pully first; its settings page was opened. */
    data object NeedsPermission : UpdateStatus

    /** [fraction] of the APK is down, or -1 while the size is not known. */
    data class Downloading(val fraction: Float) : UpdateStatus

    /** Android is waiting for approval; the foreground screen can open its confirmation again. */
    data object Confirming : UpdateStatus

    /** Preparing or waiting for the recorded Android installer session. */
    data object Installing : UpdateStatus

    data class Failed(val message: String) : UpdateStatus
}

/** What asking the server for a newer build found. */
enum class UpdateCheck { Found, UpToDate, Failed, Skipped }

/**
 * The app's update, one for the whole process: what the server offers, and the download and install. The
 * update screen, the Update command and the notification's Update all start and show the same one, and a
 * download keeps going when the page that started it goes away.
 */
object Updates {
    /**
     * The newer build the server offers, or null when there is none. While it is set, Pully shows only the update
     * screen. It starts as the last offer the phone heard of, so a newer build is asked for from the first frame,
     * before the server answers again.
     */
    var available by mutableStateOf<UpdateManifest?>(null)
        private set

    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    val busy: Boolean get() = status is UpdateStatus.Downloading || status is UpdateStatus.Installing

    /** Changes when Android sends a confirmation; the resumed update screen opens it. */
    var confirmationRequest by mutableStateOf(0)
        private set

    private var confirmation: Intent? = null
    private var confirmationOpened = false
    @Volatile private var processSession: Int? = null
    @Volatile private var operation = 0L
    private var monitoring: Job? = null
    private var listening = false

    private val scope = MainScope()

    private var checkedAt = 0L

    /** The shortest time between two checks while the app is open. */
    private const val CHECK_GAP_MS = 5 * 60_000L

    /**
     * Picks up the offer the phone last heard of, if it is still newer than this build: on start, and each time Pully comes
     * back to the front, since the background check may have heard of a build while the app sat in the background.
     */
    fun restore(context: Context) {
        monitorInstall(context.applicationContext)
        val offer = UpdatePrefs.offer(context)
        if (offer == null || offer.versionCode <= AppVersion.code(context)) {
            if (offer != null) UpdatePrefs.saveOffer(context, null)
            if ((available?.versionCode ?: Int.MAX_VALUE) <= AppVersion.code(context)) available = null
            return
        }
        if (offer.versionCode > (available?.versionCode ?: 0)) available = offer
    }

    /**
     * Asks the server for a newer build. Finding one while the app is open counts as being told about it, so the
     * background check does not notify for it as well. Coming back to the app often is not a reason to ask the server
     * as often, so a check within a few minutes of the last is [UpdateCheck.Skipped], unless [force]d.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateCheck {
        val now = System.currentTimeMillis()
        if (!force && now - checkedAt < CHECK_GAP_MS) return UpdateCheck.Skipped
        checkedAt = now
        val answer = withContext(Dispatchers.IO) {
            runCatching {
                val config = ServerStore.load(context)
                AppUpdater(context).check(config.url, config.headers(), AppVersion.code(context))
            }.onFailure { Log.w("pulls", "update check failed", it) }
        }
        // A check that failed keeps the offer; a server with nothing newer takes it away, as after switching servers.
        val manifest = answer.getOrElse { return UpdateCheck.Failed } ?: return UpdateCheck.UpToDate.also { withdraw(context) }
        if (available?.versionCode != manifest.versionCode) Analytics.track("update.event", "step" to "offered", "version" to manifest.versionName)
        available = manifest
        UpdatePrefs.saveOffer(context, manifest)
        UpdatePrefs.markNotified(context, manifest.versionCode)
        UpdateNotice.clear(context)
        return UpdateCheck.Found
    }

    /** The server has nothing newer: the update screen goes, unless a download it started is still running. */
    private fun withdraw(context: Context) {
        if (busy || UpdatePrefs.install(context) != null) return
        available = null
        status = UpdateStatus.Idle
        UpdatePrefs.saveOffer(context, null)
    }

    /** What [available] brings over the installed version, newest first. */
    fun changes(context: Context): List<ChangelogEntry> =
        available?.changesSince(AppVersion.name(context)).orEmpty()

    /**
     * Downloads the newest build, checks its digest and hands it to the installer. Android asks once for "Install
     * unknown apps"; until that is allowed this opens its settings page and waits for the next tap.
     */
    fun start(context: Context) {
        if (busy) return
        if (confirmation != null) return openConfirmation(context)
        val app = context.applicationContext
        val attemptOperation = ++operation
        UpdatePrefs.install(app)?.let {
            if (AppVersion.code(app) >= it.versionCode) return completeInstall(app)
            // An explicit retry replaces only this app's recorded attempt. Late callbacks are rejected by session ID.
            runCatching { app.packageManager.packageInstaller.abandonSession(it.sessionId) }
            clearInstall(app)
        }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = UpdateStatus.NeedsPermission
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        status = UpdateStatus.Downloading(-1f)
        scope.launch {
            var handingToInstaller = false
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val config = ServerStore.load(app)
                    val updater = AppUpdater(app)
                    val manifest = updater.check(config.url, config.headers(), AppVersion.code(app)) ?: return@runCatching null
                    withContext(Dispatchers.Main) { if (operation == attemptOperation) available = manifest }
                    UpdatePrefs.saveOffer(app, manifest)
                    var shown = -1
                    val apk = updater.download(config.url, manifest, config.headers()) { read, total ->
                        // The ring moves in whole percents, so the main thread is not asked to redraw for every buffer.
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            scope.launch { if (operation == attemptOperation && status is UpdateStatus.Downloading) status = UpdateStatus.Downloading(percent / 100f) }
                        }
                    }
                    Analytics.track("update.event", "step" to "downloaded", "version" to manifest.versionName)
                    handingToInstaller = true
                    withContext(Dispatchers.Main) {
                        check(operation == attemptOperation) { "Update attempt was replaced." }
                        status = UpdateStatus.Installing
                    }
                    updater.install(apk, expectedVersionCode = manifest.versionCode) { sessionId ->
                        // Persist before commit: Android can answer immediately or restart the process for the update.
                        check(operation == attemptOperation) { "Update attempt was replaced." }
                        processSession = sessionId
                        UpdatePrefs.saveInstall(app, UpdateInstall(sessionId, manifest.versionCode, System.currentTimeMillis()))
                        scope.launch { monitorInstall(app) }
                    }
                    manifest
                }
            }
            if (operation != attemptOperation) return@launch
            outcome
                .onSuccess { manifest ->
                    // The installer may already have answered; monitoring must preserve its newer state.
                    if (manifest == null) {
                        status = UpdateStatus.Idle
                        withdraw(app)
                    } else monitorInstall(app)
                }
                .onFailure {
                    Log.w("pulls", "update failed", it)
                    clearInstall(app)
                    status = UpdateStatus.Failed(when {
                        handingToInstaller -> "Install failed."
                        it is DigestMismatch -> "Download was damaged."
                        else -> "Download failed."
                    })
                }
        }
    }

    /**
     * Pully is in front again. Back from the install settings, the update carries on if it is allowed now, so the
     * reader does not tap twice. A recorded installer session is reconciled with the package Android actually has.
     */
    fun resume(context: Context) {
        reconcileInstall(context)
        if (status != UpdateStatus.NeedsPermission) return
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) {
            status = UpdateStatus.Idle
            start(context)
        }
    }

    /** What the system said about the install [UpdateInstallReceiver] committed. */
    internal fun installed(context: Context, sessionId: Int, result: InstallResult) {
        if (UpdatePrefs.install(context)?.sessionId != sessionId) return
        status = when (result) {
            InstallResult.Confirming -> UpdateStatus.Confirming
            InstallResult.Installed -> {
                val attempt = UpdatePrefs.install(context) ?: return
                if (AppVersion.code(context) >= attempt.versionCode) return completeInstall(context)
                clearInstall(context)
                UpdateStatus.Failed("Installed version did not match.")
            }
            // Cancelling the system dialog is a choice, not a failure: the update screen offers Update again.
            InstallResult.Cancelled -> UpdateStatus.Idle.also { clearInstall(context) }
            // The installer's own words are for the log; the update screen has one short line.
            is InstallResult.Failed -> UpdateStatus.Failed("Install failed.").also {
                clearInstall(context)
                Log.w("pulls", "install failed: ${result.message}")
            }
        }
    }

    internal fun confirm(context: Context, sessionId: Int, intent: Intent) {
        if (UpdatePrefs.install(context)?.sessionId != sessionId) return
        processSession = sessionId
        confirmation = intent
        confirmationOpened = false
        confirmationRequest++
        status = UpdateStatus.Confirming
        monitorInstall(context.applicationContext)
    }

    /** Called by the visible activity, so Android's background activity launch rules cannot hide the approval. */
    fun openConfirmation(context: Context, automatic: Boolean = false) {
        val intent = confirmation ?: return
        if (automatic && confirmationOpened) return
        confirmationOpened = true
        runCatching { context.startActivity(intent) }.onFailure {
            confirmationOpened = false
            status = UpdateStatus.Failed("Could not open installer.")
            Log.w("pulls", "installer confirmation could not open", it)
        }
    }

    private fun clearInstall(context: Context) {
        UpdatePrefs.saveInstall(context, null)
        processSession = null
        confirmation = null
        confirmationOpened = false
    }

    private fun completeInstall(context: Context) {
        Analytics.track("update.event", "step" to "installed", "version" to AppVersion.name(context))
        clearInstall(context)
        status = UpdateStatus.Idle
        val offer = available ?: UpdatePrefs.offer(context)
        if ((offer?.versionCode ?: 0) <= AppVersion.code(context)) {
            available = null
            UpdatePrefs.saveOffer(context, null)
        } else available = offer
        UpdateNotice.clear(context)
    }

    private fun reconcileInstall(context: Context) {
        val attempt = UpdatePrefs.install(context) ?: return
        val sessionExists = context.packageManager.packageInstaller.getSessionInfo(attempt.sessionId) != null
        when (recoverUpdateInstall(attempt, AppVersion.code(context), sessionExists, processSession == attempt.sessionId, System.currentTimeMillis())) {
            InstallRecovery.Installed -> completeInstall(context)
            InstallRecovery.Interrupted, InstallRecovery.TimedOut -> {
                confirmation = null
                confirmationOpened = false
                status = UpdateStatus.Failed(if (sessionExists && processSession == attempt.sessionId) "Installation did not finish." else "Installation was interrupted.")
            }
            InstallRecovery.Waiting -> if (status == UpdateStatus.Idle) status = UpdateStatus.Installing
        }
    }

    private fun monitorInstall(context: Context) {
        if (!listening) {
            listening = true
            context.packageManager.packageInstaller.registerSessionCallback(object : PackageInstaller.SessionCallback() {
                override fun onCreated(sessionId: Int) {}
                override fun onBadgingChanged(sessionId: Int) {}
                override fun onActiveChanged(sessionId: Int, active: Boolean) {}
                override fun onProgressChanged(sessionId: Int, progress: Float) {}
                override fun onFinished(sessionId: Int, success: Boolean) {
                    installed(context, sessionId, if (success) InstallResult.Installed else InstallResult.Failed("Installer session ended."))
                }
            })
        }
        reconcileInstall(context)
        if (monitoring?.isActive == true || UpdatePrefs.install(context) == null) return
        monitoring = scope.launch {
            while (UpdatePrefs.install(context) != null) {
                delay(1_000)
                reconcileInstall(context)
            }
        }
    }
}

/** The installed build's version, from the package manager. */
object AppVersion {
    fun code(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    fun name(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** True once this install has been replaced by an update at least once, rather than installed fresh. */
    fun wasUpdated(context: Context): Boolean {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.lastUpdateTime > info.firstInstallTime
    }
}

/** What the phone remembers about updates: the newest build it was told about, and the last changelog it showed. */
internal object UpdatePrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("pulls-update", Context.MODE_PRIVATE)

    fun notifiedCode(context: Context): Int = prefs(context).getInt("notifiedCode", 0)

    fun markNotified(context: Context, code: Int) {
        if (code > notifiedCode(context)) prefs(context).edit().putInt("notifiedCode", code).apply()
    }

    fun seenVersion(context: Context): String? = prefs(context).getString("seenVersion", null)

    fun markSeen(context: Context, version: String) = prefs(context).edit().putString("seenVersion", version).apply()

    fun askedForNotifications(context: Context): Boolean = prefs(context).getBoolean("askedNotifications", false)

    fun markAskedForNotifications(context: Context) = prefs(context).edit().putBoolean("askedNotifications", true).apply()

    /** The last newer build the server offered, kept so the next start asks for it before the server answers again. */
    fun offer(context: Context): UpdateManifest? =
        prefs(context).getString("offer", null)?.let { runCatching { parseUpdateManifest(it) }.getOrNull() }

    fun saveOffer(context: Context, manifest: UpdateManifest?) {
        prefs(context).edit().apply { if (manifest == null) remove("offer") else putString("offer", Json.encodeToString(manifest)) }.apply()
    }

    fun install(context: Context): UpdateInstall? = prefs(context).getString("install", null)?.let {
        runCatching { Json.decodeFromString<UpdateInstall>(it) }.getOrNull()
    }

    fun saveInstall(context: Context, attempt: UpdateInstall?) {
        val edit = prefs(context).edit()
        if (attempt == null) edit.remove("install") else edit.putString("install", Json.encodeToString(attempt))
        check(edit.commit()) { "Could not remember installer session." }
    }
}

/** The changelog this build carries, from `android/CHANGELOG.md`, and which part of it the reader has not seen. */
object WhatsNew {
    fun all(context: Context): List<ChangelogEntry> =
        runCatching { context.assets.open("changelog.md").bufferedReader().use { parseChangelog(it.readText()) } }
            .onFailure { Log.w("pulls", "changelog missing", it) }
            .getOrDefault(emptyList())

    /**
     * The sections to show once after an update: every version above the one whose changelog was last shown. The
     * first run that has no record shows this version's own section, but only when the app was updated rather than
     * installed fresh; a fresh install just records where it starts.
     */
    fun pending(context: Context): List<ChangelogEntry> {
        val current = AppVersion.name(context)
        val seen = UpdatePrefs.seenVersion(context)
        if (seen == null && !AppVersion.wasUpdated(context)) {
            UpdatePrefs.markSeen(context, current)
            return emptyList()
        }
        return all(context).since(seen, current).also { if (it.isEmpty()) UpdatePrefs.markSeen(context, current) }
    }

    fun markSeen(context: Context) = UpdatePrefs.markSeen(context, AppVersion.name(context))
}
