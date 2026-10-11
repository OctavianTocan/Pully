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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiffScreen(target: OpenTarget, path: String, focusLine: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<LineThread>>(emptyList()) }
    var notesTick by remember { mutableStateOf(0) }
    var painted by remember { mutableStateOf(Painted(emptyList(), "#1B3A2A", "#3F1D20")) }
    var replyFor by remember { mutableStateOf<Int?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }
    var draftLine by remember { mutableStateOf<Int?>(null) }
    var draftText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(path, notesTick) {
        val loaded = withContext(Dispatchers.IO) { runCatching { loadOpened(context, target) } }
        loaded.onSuccess { opened = it }.onFailure { error = it.message }
        if (target is OpenTarget.Pull) {
            notes = withContext(Dispatchers.IO) { runCatching { lineThreads(context, target, path) }.getOrDefault(emptyList()) }
        }
    }
    val lines = opened?.files?.firstOrNull { it.path == path }?.patchLines.orEmpty()
    LaunchedEffect(path, lines) {
        if (lines.isEmpty()) return@LaunchedEffect
        painted = withContext(Dispatchers.IO) {
            runCatching { highlight(context, path, lines.map { it.text }) }.getOrDefault(painted)
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }, actions = {
            FileLinksMenu(target, path, opened)
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
            answer?.let { Text(it, Modifier.padding(12.dp)) }
            val file = opened?.files?.firstOrNull { it.path == path }
            val oneSided = file != null && (file.additions == 0 || file.deletions == 0)
            var layout by remember { mutableStateOf(DiffLayout.load(context)) }
            val code: @Composable (Int) -> androidx.compose.ui.text.AnnotatedString = { index ->
                val tokens = painted.lines.getOrNull(index)
                if (tokens == null) CodeHighlight.text(path, lines[index].text)
                else androidx.compose.ui.text.buildAnnotatedString {
                    tokens.forEach { token ->
                        if (token.color.isBlank()) append(token.text)
                        else withStyle(SpanStyle(color = colorOf(token.color, 0xFFEDEDED))) { append(token.text) }
                    }
                }
            }
            val lineBackground: @Composable (Int) -> Color = { index ->
                val line = lines[index]
                val focused = focusLine > 0 && (line.newLine == focusLine || line.oldLine == focusLine)
                when {
                    index in selected -> MaterialTheme.colorScheme.secondaryContainer
                    focused -> MaterialTheme.colorScheme.tertiaryContainer
                    line.kind == '+' -> colorOf(painted.added, 0xFF1B3A2A)
                    line.kind == '-' -> colorOf(painted.removed, 0xFF3F1D20)
                    else -> MaterialTheme.colorScheme.surface
                }
            }
            val toggle: (Int) -> Unit = { index -> selected = if (index in selected) selected - index else selected + index }
            val extras: @Composable (Int) -> Unit = { index ->
                val line = lines[index]
                val lineNo = if (line.kind == '-') line.oldLine else line.newLine
                notes.filter { it.line == lineNo && it.deletions == (line.kind == '-') }.forEach { thread ->
                    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, bottom = 6.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        thread.comments.forEach { comment ->
                            Text(comment.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { replyFor = thread.rootId }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reply") }
                            if (thread.threadId != null && !thread.resolved && target is OpenTarget.Pull) {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching { api(context).call("review-resolve", buildJsonObject {
                                                put("owner", target.owner); put("repo", target.repo); put("number", target.number); put("threadId", thread.threadId)
                                            }) }
                                        }
                                        notesTick += 1
                                    }
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resolve") }
                            }
                        }
                        if (replyFor == thread.rootId && target is OpenTarget.Pull) {
                            OutlinedTextField(replyText, { replyText = it }, Modifier.fillMaxWidth(), label = { Text("Reply") }, singleLine = true)
                            Button(onClick = {
                                val text = replyText.trim()
                                if (text.isEmpty()) return@Button
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { api(context).call("review-reply", buildJsonObject {
                                            put("owner", target.owner); put("repo", target.repo); put("number", target.number); put("rootId", thread.rootId); put("body", text)
                                        }) }
                                    }
                                    replyText = ""
                                    replyFor = null
                                    notesTick += 1
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Send reply") }
                        }
                    }
                }
                if (draftLine == lineNo && target is OpenTarget.Pull) {
                    OutlinedTextField(draftText, { draftText = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Comment on this line") })
                    Button(onClick = {
                        val text = draftText.trim()
                        val review = opened
                        if (text.isEmpty() || review == null || lineNo == null) return@Button
                        val side = if (line.kind == '-') "deletions" else "additions"
                        scope.launch {
                            answer = postLineComment(context, target, path, side, lineNo, review.headSha, text)
                            draftText = ""
                            draftLine = null
                            notesTick += 1
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp)) { Text("Post comment") }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= SPLIT_MIN_WIDTH
            val split = wide && !oneSided && layout == DiffLayout.SPLIT
            Column {
            if (wide && !oneSided) {
                LayoutSwitch(layout) { layout = it; DiffLayout.save(context, it) }
            }
            if (split) {
                val rows = remember(lines) { splitRows(lines) }
                LazyColumn {
                    items(rows.size) { rowIndex ->
                        val row = rows[rowIndex]
                        SplitLine(
                            row = row,
                            lines = lines,
                            code = code,
                            background = lineBackground,
                            commentable = target is OpenTarget.Pull,
                            onComment = { index -> draftLine = lines[index].let { if (it.kind == '-') it.oldLine else it.newLine } },
                            onToggle = toggle,
                        )
                        SplitExtras(row, extras)
                    }
                }
            } else LazyColumn {
                items(lines.size) { index ->
                    val line = lines[index]
                    val lineNo = if (line.kind == '-') line.oldLine else line.newLine
                    if (target is OpenTarget.Pull && lineNo != null) {
                        TextButton(onClick = { draftLine = lineNo }, modifier = Modifier.heightIn(min = 32.dp)) { Text("+") }
                    }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("${(line.newLine ?: line.oldLine ?: 0).toString().padStart(4)} ${line.kind} ")
                            append(code(index))
                        },
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(lineBackground(index)).clickable { toggle(index) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                    )
                    extras(index)
                }
            }
            }
            }
            if (selected.isNotEmpty()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(question, { question = it }, Modifier.weight(1f), label = { Text("Ask about these lines") }, singleLine = true)
                    Button(
                        onClick = {
                            val text = question.trim()
                            val review = opened
                            if (text.isEmpty() || review == null) return@Button
                            scope.launch {
                                answer = ask(context, review.key, path, lines, selected, text)
                                question = ""
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ask") }
                }
            }
        }
    }
}

internal suspend fun ask(
    context: Context,
    key: String,
    path: String,
    lines: List<com.octaviantocan.pulls.data.DiffLine>,
    selected: Set<Int>,
    question: String,
): String = withContext(Dispatchers.IO) {
    val chosen = selected.map { lines[it] }
    val numbers = chosen.mapNotNull { it.newLine ?: it.oldLine }
    if (numbers.isEmpty()) return@withContext "Select lines that have a number."
    val side = if (chosen.any { it.kind == '-' } && chosen.none { it.kind == '+' }) "deletions" else "additions"
    val id = api(context).call("ai-task-start", buildJsonObject {
        put("kind", "ask")
        put("key", key)
        put("threadId", "ask-${System.currentTimeMillis().toString(36)}")
        put("question", question)
        val config = ServerStore.load(context)
        put("model", buildJsonObject {
            put("engine", config.engine)
            if (config.model.isNotBlank()) put("model", config.model)
        })
        put("selection", buildJsonObject {
            put("path", path)
            put("side", side)
            put("startLine", numbers.min())
            put("endLine", numbers.max())
        })
    }).jsonPrimitive.content
    follow(context, id)
}

internal suspend fun postLineComment(context: Context, target: OpenTarget.Pull, path: String, side: String, line: Int, headSha: String, body: String): String = withContext(Dispatchers.IO) {
    runCatching {
        api(context).call("review-comment", buildJsonObject {
            put("owner", target.owner)
            put("repo", target.repo)
            put("number", target.number)
            put("path", path)
            put("side", side)
            put("line", line)
            put("headSha", headSha)
            put("body", body)
        })
        "Comment posted."
    }.getOrElse { it.message ?: "Could not post the comment." }
}

internal data class LineComment(val author: String, val body: String)

internal data class LineThread(val rootId: Int, val threadId: String?, val line: Int, val deletions: Boolean, val resolved: Boolean, val comments: List<LineComment>)

internal fun lineThreads(context: Context, target: OpenTarget.Pull, path: String): List<LineThread> {
    val result = api(context).call("review-threads", buildJsonObject {
        put("owner", target.owner)
        put("repo", target.repo)
        put("number", target.number)
    }).jsonObject
    return result["threads"]?.jsonArray.orEmpty().mapNotNull { thread ->
        val item = thread.jsonObject
        if (item["path"]?.jsonPrimitive?.content != path) return@mapNotNull null
        val line = item["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
        val rootId = item["rootId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
        LineThread(
            rootId = rootId,
            threadId = item["threadId"]?.jsonPrimitive?.contentOrNull,
            line = line,
            deletions = item["side"]?.jsonPrimitive?.content == "deletions",
            resolved = item["resolved"]?.jsonPrimitive?.contentOrNull == "true",
            comments = item["comments"]?.jsonArray.orEmpty().map { comment ->
                val row = comment.jsonObject
                LineComment(row["author"]?.jsonPrimitive?.contentOrNull ?: "review", row["body"]?.jsonPrimitive?.contentOrNull ?: "")
            },
        )
    }
}

internal data class Paint(val text: String, val color: String)

internal data class Painted(val lines: List<List<Paint>>, val added: String, val removed: String)

internal fun colorOf(hex: String, fallback: Long): androidx.compose.ui.graphics.Color {
    val raw = hex.removePrefix("#")
    val rgb = raw.take(6).toLongOrNull(16) ?: return androidx.compose.ui.graphics.Color(fallback)
    val alpha = if (raw.length >= 8) raw.drop(6).toLongOrNull(16) ?: 0xFF else 0xFF
    return androidx.compose.ui.graphics.Color((alpha shl 24) or rgb)
}

internal fun highlight(context: Context, path: String, lines: List<String>): Painted {
    val result = api(context).call("highlight", buildJsonObject {
        put("path", path)
        put("dark", true)
        put("lines", kotlinx.serialization.json.JsonArray(lines.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }).jsonObject
    val theme = result["theme"]?.jsonObject
    return Painted(
        lines = result["lines"]?.jsonArray.orEmpty().map { line ->
            line.jsonArray.map { token ->
                val row = token.jsonObject
                Paint(row["text"]?.jsonPrimitive?.content ?: "", row["color"]?.jsonPrimitive?.content ?: "")
            }
        },
        added = theme?.get("added")?.jsonPrimitive?.content ?: "",
        removed = theme?.get("removed")?.jsonPrimitive?.content ?: "",
    )
}
