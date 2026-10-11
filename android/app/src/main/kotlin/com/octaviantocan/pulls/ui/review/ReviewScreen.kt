package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.ai.ChatWidget

/**
 * Owner: review-page worker. Port of `DiffsPage.vue` + `diff.vue`: one scrolling page,
 * header and overview first, then [diffBodyItems] LAST (it emits the end-of-diff footer).
 */
@Composable
fun ReviewScreen(target: OpenTarget, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(target) { ReviewSession(target, Repo(context), scope, AppSettings.get(context)).also { it.load() } }
    DisposableEffect(session) { onDispose { session.detach() } }
    val listState = rememberLazyListState()
    val ui = rememberDiffUi(session)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            diffBodyItems(session, ui)
        }
        DiffFocusEffect(session, ui, listState)
        ChatWidget(session)
    }
}
