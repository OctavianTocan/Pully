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
internal fun ReviewScreen(target: OpenTarget, onBack: () -> Unit, onFile: (String, Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedReview?>(null) }
    var status by remember { mutableStateOf("Loading…") }
    var taskNote by remember { mutableStateOf<String?>(null) }
    var lenses by remember { mutableStateOf<List<String>>(emptyList()) }
    var lens by remember { mutableStateOf<String?>(null) }
    var chat by rememberSaveable { mutableStateOf("") }
    var saved by remember { mutableStateOf<SavedReview?>(null) }
    var runningId by remember { mutableStateOf<String?>(null) }
    var fileQuery by rememberSaveable { mutableStateOf("") }
    var showTree by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(target.serverTarget) {
        val loaded = withContext(Dispatchers.IO) { runCatching { loadOpened(context, target) } }
        val names = withContext(Dispatchers.IO) { runCatching { lensNames(context) }.getOrDefault(emptyList()) }
        lenses = names
        loaded.onSuccess {
            opened = it
            status = "${it.files.size} files"
            saved = runCatching { api(context).call("review-state", args("key" to it.key)).asSaved() }.getOrNull()
        }.onFailure { status = it.message ?: "Failed" }
    }
    val review = opened
    Scaffold(topBar = {
        TopAppBar(title = { Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { taskNote = runTask(context, review?.key, "analyze") { runningId = it } } }, enabled = review != null && runningId == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Analyze") }
                Button(onClick = { scope.launch { taskNote = runTask(context, review?.key, "critique", lens) { runningId = it } } }, enabled = review != null && runningId == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review") }
                if (runningId != null) Button(onClick = { scope.launch { stopTask(context, runningId); runningId = null; taskNote = "Stopped" } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
            }
            if (lenses.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { lens = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (lens == null) "Lens: none" else "No lens") }
                    lenses.take(4).forEach { name ->
                        TextButton(onClick = { lens = name }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (lens == name) "• $name" else name) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(chat, { chat = it }, Modifier.weight(1f), label = { Text("Chat") }, singleLine = true)
                Button(
                    onClick = {
                        val text = chat.trim()
                        if (text.isNotEmpty() && review != null) {
                            scope.launch {
                                taskNote = runTask(context, review.key, "chat", text = text)
                                chat = ""
                            }
                        }
                    },
                    enabled = review != null && chat.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Send") }
            }
            Text(status, Modifier.padding(20.dp, 8.dp))
            taskNote?.let { Text(it, Modifier.padding(horizontal = 20.dp)) }
            saved?.findings?.take(12)?.forEach { finding ->
                TextButton(onClick = { onFile(finding.path, finding.line) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${finding.severity} ${finding.path}:${finding.line} ${finding.title}", maxLines = 2)
                        if (finding.body.isNotBlank()) Text(finding.body, maxLines = 6, style = MaterialTheme.typography.bodyMedium)
                        if (finding.suggestion.isNotBlank()) Text(finding.suggestion, maxLines = 6, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            saved?.threads?.take(6)?.forEach { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), maxLines = 3) }
            saved?.chat?.takeLast(4)?.forEach { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), maxLines = 4) }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { showTree = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (!showTree) "• Groups" else "Groups") }
                TextButton(onClick = { showTree = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showTree) "• Tree" else "Tree") }
            }
            if (!showTree) OutlinedTextField(fileQuery, { fileQuery = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Find a file") }, singleLine = true)
            if (showTree) FileTree(review?.files.orEmpty(), review?.key ?: "") { path -> onFile(path, 0) }
            LazyColumn {
                if (showTree) return@LazyColumn
                val groups = review?.groups.orEmpty().map { group ->
                    group.copy(paths = group.paths.filter { fileQuery.isBlank() || fileQuery.lowercase() in it.lowercase() })
                }.filter { it.paths.isNotEmpty() }
                if (groups.isEmpty()) {
                    items(review?.files.orEmpty(), key = { it.path }) { file -> FileRow(file) { onFile(file.path, 0) } }
                } else {
                    groups.forEach { group ->
                        item(key = "group-${group.label}") {
                            Column(Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp)) {
                                Text(group.label, style = MaterialTheme.typography.titleMedium)
                                if (group.summary.isNotBlank()) Text(group.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(group.paths, key = { path -> "${group.label}/$path" }) { path ->
                            val file = review?.files?.firstOrNull { it.path == path }
                            if (file != null) FileRow(file) { onFile(path, 0) } else Text(path, Modifier.padding(20.dp, 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileRow(file: com.octaviantocan.pulls.data.ReviewFile, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("+${file.additions} −${file.deletions}  ${file.status}", style = MaterialTheme.typography.labelMedium)
        }
    }
    HorizontalDivider()
}
