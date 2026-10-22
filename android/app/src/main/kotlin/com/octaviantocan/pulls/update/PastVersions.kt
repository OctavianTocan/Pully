package com.octaviantocan.pulls.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.PastVersion
import com.octaviantocan.pulls.core.DigestMismatch
import com.octaviantocan.pulls.core.parsePastVersions
import com.octaviantocan.pulls.data.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What getting a past version is doing, for the Past versions page. */
sealed interface PastStatus {
    data object Idle : PastStatus

    /** Android needs "Install unknown apps" for Pully first; its settings page was opened. */
    data class NeedsPermission(val version: PastVersion) : PastStatus

    /** [fraction] of the APK is down, or -1 while the size is not known. */
    data class Downloading(val version: PastVersion, val fraction: Float) : PastStatus

    /** The system install dialog is up. */
    data class Confirming(val version: PastVersion) : PastStatus

    /** Handed to the installer, which has not answered yet. */
    data class Installing(val version: PastVersion) : PastStatus

    /** Installed while the page was waiting for it: the page opens it. */
    data class Ready(val version: PastVersion) : PastStatus

    data class Failed(val version: PastVersion, val message: String) : PastStatus
}

/**
 * The past versions of Pully behind the dev-only slider in Settings, Advanced. Each is the app as it was, rebuilt from
 * git by `android/scripts/build-past-ui.py` as its own app beside this one, with no launcher icon: Pully lists them
 * from the server, installs the one picked through the system installer, and opens it with this phone's server and
 * Access token, which the old copy takes on start. Nothing here changes this app, which always runs the newest UI.
 */
object PastVersions {
    /** The category every past build's activity answers, and nothing else does. */
    const val CATEGORY = "com.octaviantocan.pulls.category.PAST_UI"

    // Read by PastUiBoot in each past build (android/scripts/past-ui/PastUiBoot.kt).
    private const val EXTRA_URL = "com.octaviantocan.pulls.past.URL"
    private const val EXTRA_ACCESS_ID = "com.octaviantocan.pulls.past.ACCESS_ID"
    private const val EXTRA_ACCESS_SECRET = "com.octaviantocan.pulls.past.ACCESS_SECRET"

    /** What build-past-ui.py adds to a version's own versionCode, so no update is ever newer than a past build. */
    const val CODE_OFFSET = 1_000_000

    var status by mutableStateOf<PastStatus>(PastStatus.Idle)
        private set

    val busy: Boolean get() = status is PastStatus.Downloading || status is PastStatus.Confirming || status is PastStatus.Installing

    private val scope = MainScope()

    private fun prefs(context: Context) = context.getSharedPreferences("pulls-past", Context.MODE_PRIVATE)

    /** The list as the server last gave it, so the page shows at once (rule 128). */
    fun cached(context: Context): List<PastVersion> =
        prefs(context).getString("index", null)?.let { text -> runCatching { parsePastVersions(text, AppVersion.code(context)) }.getOrNull() }.orEmpty()

    /** Asks the server for its list and keeps it; null when the server could not be asked. */
    suspend fun refresh(context: Context): List<PastVersion>? = withContext(Dispatchers.IO) {
        runCatching {
            val config = ServerStore.load(context)
            val text = AppUpdater(context).get(config.url, "download/past/index.json", config.headers())
            parsePastVersions(text, AppVersion.code(context)).also { prefs(context).edit().putString("index", text).apply() }
        }.onFailure { Log.w("pulls", "past versions list failed", it) }.getOrNull()
    }

    /** True when [version]'s own build is installed, not an older or newer rebuild under the same package. */
    fun installed(context: Context, version: PastVersion): Boolean = try {
        val info = context.packageManager.getPackageInfo(version.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        code == (CODE_OFFSET + version.versionCode).toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Opens the installed [version] with this phone's server; false when it is not installed, or when the package under
     * that name is not signed with this app's key, which keeps the Access token from an impostor.
     */
    fun open(context: Context, version: PastVersion): Boolean {
        if (context.packageManager.checkSignatures(context.packageName, version.packageName) != PackageManager.SIGNATURE_MATCH) return false
        val find = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY).setPackage(version.packageName)
        val activity = context.packageManager.queryIntentActivities(find, 0).firstOrNull()?.activityInfo ?: return false
        val config = ServerStore.load(context)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY).setClassName(activity.packageName, activity.name)
            .putExtra(EXTRA_URL, config.url)
            .putExtra(EXTRA_ACCESS_ID, config.accessId)
            .putExtra(EXTRA_ACCESS_SECRET, config.accessSecret)
        return runCatching { context.startActivity(intent) }.onFailure { Log.w("pulls", "could not open ${version.versionName}", it) }.isSuccess
    }

    /** Asks Android to uninstall [version]; the system asks first. */
    fun remove(context: Context, version: PastVersion) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${version.packageName}"))) }
    }

    /**
     * Downloads [version], checks it against the digest the server listed and installs it as its own package, then the
     * page opens it. Android asks once for "Install unknown apps", as for updates.
     */
    fun get(context: Context, version: PastVersion) {
        if (busy) return
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = PastStatus.NeedsPermission(version)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            }
            return
        }
        status = PastStatus.Downloading(version, -1f)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val config = ServerStore.load(app)
                    val updater = AppUpdater(app)
                    val url = "${config.url.trimEnd('/')}/download/past/${version.versionName}.apk"
                    var shown = -1
                    val apk = updater.download(config.url, url, version.sha256, config.headers(), File(app.cacheDir, "pulls-past.apk")) { read, total ->
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            scope.launch { if (status is PastStatus.Downloading) status = PastStatus.Downloading(version, percent / 100f) }
                        }
                    }
                    updater.install(apk, packageName = version.packageName)
                }
            }
            outcome
                .onSuccess { if (status is PastStatus.Downloading) status = PastStatus.Installing(version) }
                .onFailure {
                    Log.w("pulls", "past version ${version.versionName} failed", it)
                    status = PastStatus.Failed(version, if (it is DigestMismatch) "Download was damaged." else "Download failed.")
                }
        }
    }

    /**
     * Pully is in front again. Back from the system dialog without an answer (it was left, not cancelled), Get is
     * offered again, as the update screen does. Back from the install settings with it allowed now, the download starts
     * without a second tap.
     */
    fun resume(context: Context) {
        if (status is PastStatus.Confirming) status = PastStatus.Idle
        val waiting = (status as? PastStatus.NeedsPermission)?.version ?: return
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) {
            status = PastStatus.Idle
            get(context, waiting)
        }
    }

    /** The page opened what was [PastStatus.Ready], or let a failure go. */
    fun settle() {
        if (!busy) status = PastStatus.Idle
    }

    /** What the system said about the install [UpdateInstallReceiver] committed. */
    internal fun installed(result: InstallResult) {
        val version = when (val now = status) {
            is PastStatus.Downloading -> now.version
            is PastStatus.Confirming -> now.version
            is PastStatus.Installing -> now.version
            // A dialog left and answered later still lands: the page offers Open for what was installed.
            else -> return
        }
        status = when (result) {
            InstallResult.Confirming -> PastStatus.Confirming(version)
            InstallResult.Installed -> PastStatus.Ready(version)
            InstallResult.Cancelled -> PastStatus.Idle
            is InstallResult.Failed -> PastStatus.Failed(version, "Install failed.").also { Log.w("pulls", "past install failed: ${result.message}") }
        }
    }
}
