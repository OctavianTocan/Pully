package com.octaviantocan.pulls.ui

import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.PullsApi
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.args
import com.octaviantocan.pulls.data.OpenedReview
import com.octaviantocan.pulls.data.SavedReview
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.data.asOpened
import com.octaviantocan.pulls.data.asSaved
import com.octaviantocan.pulls.data.asPulls
import com.octaviantocan.pulls.data.asTask
import com.octaviantocan.pulls.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal suspend fun stopTask(context: Context, id: String?) {
    if (id != null) withContext(Dispatchers.IO) { runCatching { api(context).call("ai-task-cancel", args("id" to id)) } }
}

internal suspend fun runTask(context: Context, key: String?, kind: String, lens: String? = null, text: String? = null, onId: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
    if (key == null) return@withContext "Open the diff first."
    val body = buildJsonObject {
        put("kind", kind)
        put("key", key)
        val config = ServerStore.load(context)
        put("model", buildJsonObject {
            put("engine", config.engine)
            if (config.model.isNotBlank()) put("model", config.model)
        })
        if (kind == "analyze" || kind == "chat") put("locale", "en")
        if (kind == "critique") put("variant", listOf("android", lens).filterNotNull().joinToString(":"))
        if (!lens.isNullOrBlank()) put("lens", lens)
        if (!text.isNullOrBlank()) put("text", text)
    }
    val id = api(context).call("ai-task-start", body).jsonPrimitive.content
    onId(id)
    follow(context, id)
}

internal suspend fun follow(context: Context, id: String): String {
    var after = 0
    var last = "Running"
    while (true) {
        val snap = api(context).call("ai-task-wait", buildJsonObject {
            put("id", id)
            put("after", after)
        }).asTask()
        after = snap.rev
        last = snap.activities.lastOrNull() ?: snap.status
        if (snap.status != "running") return snap.error ?: snap.result?.take(280) ?: "Finished ($last)"
    }
}

internal suspend fun lensNames(context: Context): List<String> {
    val result = api(context).call("lens-list")
    return result.jsonArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
}

internal suspend fun loadOpened(context: Context, target: OpenTarget): OpenedReview = when (target) {
    is OpenTarget.Pull -> api(context).call("github-pr", buildJsonObject {
        put("owner", target.owner)
        put("repo", target.repo)
        put("number", target.number)
    }).asOpened()
    is OpenTarget.Compare -> api(context).call("github-compare", buildJsonObject {
        put("owner", target.owner)
        put("repo", target.repo)
        put("base", target.base)
        put("head", target.head)
    }).asOpened()
    is OpenTarget.Commit -> api(context).call("github-commit", buildJsonObject {
        put("owner", target.owner)
        put("repo", target.repo)
        put("sha", target.sha)
    }).asOpened()
    is OpenTarget.Branch -> {
        val info = api(context).call("repo-info").jsonObject
        val base = info["defaultBranch"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "HEAD"
        api(context).call("local-diff", args("target" to "$base...${target.name}")).asOpened()
    }
    OpenTarget.Worktree -> api(context).call("local-diff", args("target" to "")).asOpened()
}

internal fun api(context: Context): PullsApi {
    val config = ServerStore.load(context)
    return PullsApi(config.url, config.headers())
}
