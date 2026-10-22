package com.octaviantocan.pulls.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.FeedbackDraft
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The route under Settings/Commands, retained by the existing PullsApp entrypoint. */
internal object FeedbackFrom { var place: String? = null }

private object AndroidFeedback {
    private var services: FeedbackServices? = null
    private var epoch = -1L
    private var observing = false
    fun get(context: Context): FeedbackServices {
        if (!observing) {
            observing = true
            RepoIdentity.observeInvalidation { services?.close(); services = null }
        }
        val generation = RepoIdentity.generation
        services?.takeIf { epoch == generation && it.current() }?.let { return it }
        services?.close()
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        return SourceFeedbackServices(
            Repo(app), AppSettings.get(app),
            FeedbackMetadata(info.versionName.orEmpty(), code, Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(),
                "Android ${Build.VERSION.RELEASE}", android = Build.VERSION.RELEASE.orEmpty(), sdk = Build.VERSION.SDK_INT),
            { Analytics.screen }, { FeedbackFrom.place }, { generation == RepoIdentity.generation }, FeedbackDraft,
        ).also { services = it; epoch = generation }
    }
}

@Composable
internal fun rememberAndroidFeedbackServices(): FeedbackServices {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(context) {
        val stop = RepoIdentity.observeInvalidation { scope.launch(Dispatchers.Main.immediate) { epoch = RepoIdentity.generation } }
        epoch = RepoIdentity.generation
        onDispose { stop() }
    }
    return remember(context, epoch) { AndroidFeedback.get(context) }
}

@Composable
internal fun FeedbackSheet(onClose: () -> Unit) = FeedbackSheet(rememberAndroidFeedbackServices(), onClose)

@Composable
internal fun ColumnScope.FeedbackPage(onSent: () -> Unit) = FeedbackPage(rememberAndroidFeedbackServices(), onSent)
