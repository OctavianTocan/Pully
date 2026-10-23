package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.ui.home.androidHomeScreenData
import com.octaviantocan.pulls.ui.home.rememberAndroidNativeHomeActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Thin adapters only; all Overview layout, text, lists and editing state live in common code. */
@Composable
internal fun rememberAndroidOverviewServices(): OverviewServices {
    val native = rememberAndroidNativeHomeActions()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(scope) {
        val unsubscribe = RepoIdentity.observeInvalidation {
            scope.launch(Dispatchers.Main.immediate) { epoch = RepoIdentity.generation }
        }
        if (epoch != RepoIdentity.generation) epoch = RepoIdentity.generation
        onDispose { unsubscribe() }
    }
    val data = remember(context, epoch) { androidHomeScreenData(context, Repo(context)) }
    return remember(native, data) {
        object : OverviewServices {
            override val native = native
            override val sizes = data.sizes
            override val ai = AndroidOverviewAiContent
            override val canEditText = true

            @Composable override fun EditBack(onBack: () -> Unit) {
                // motion-ok: Back leaves the field with what it had before, the way Escape would; no page moves, so none follows the finger.
                androidx.activity.compose.BackHandler(onBack = onBack)
            }

            @Composable override fun TrackSearch(scope: String, query: String, count: Int) {
                com.octaviantocan.pulls.ui.TrackSearch(scope, query, count)
            }
        }
    }
}
