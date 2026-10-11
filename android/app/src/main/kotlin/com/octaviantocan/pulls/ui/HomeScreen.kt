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
internal fun HomeScreen(notice: String?, onOpen: (OpenTarget) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var listState by rememberSaveable { mutableStateOf("open") }
    var queue by rememberSaveable { mutableStateOf("all") }
    var role by rememberSaveable { mutableStateOf("all") }
    var pulls by remember { mutableStateOf<List<PullRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(listState, reload) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { api(context).call("my-pulls", args("state" to listState)).asPulls() }
        }
        loading = false
        loaded.onSuccess { pulls = it }.onFailure { error = it.message }
    }
    val parsed = PrLinks.parse(text)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Pulls") }, actions = {
            TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Server") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (notice != null) item { Text(notice, Modifier.padding(20.dp, 8.dp)) }
            item {
                Column(Modifier.padding(20.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pull request link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { parsed?.let(onOpen) }),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { parsed?.let(onOpen) }, enabled = parsed != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(parsed?.label?.let { "Open $it" } ?: "Open") }
                        Button(onClick = { onOpen(OpenTarget.Worktree) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Worktree") }
                        TextButton(onClick = { reload += 1 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("open", "closed").forEach { value ->
                            TextButton(onClick = { listState = value }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (listState == value) "• $value" else value) }
                        }
                    }
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search pull requests") }, singleLine = true)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (loading) Text("Loading your pull requests…")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Triage.queues.forEach { item ->
                            TextButton(onClick = { queue = item }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (queue == item) "• $item" else item, maxLines = 1) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Triage.roles.forEach { item ->
                            TextButton(onClick = { role = item }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (role == item) "• $item" else item, maxLines = 1) }
                        }
                    }
                }
            }
            items(Triage.visible(pulls, queue, role, search.trim().lowercase(), System.currentTimeMillis()), key = { "${it.owner}/${it.repo}#${it.number}" }) { pull ->
                TextButton(
                    onClick = { onOpen(OpenTarget.Pull(pull.owner, pull.repo, pull.number)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${pull.owner}/${pull.repo}#${pull.number}", style = MaterialTheme.typography.labelLarge)
                        Text(pull.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
