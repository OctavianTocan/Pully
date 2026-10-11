package com.octaviantocan.pulls.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.OpenedReview

/**
 * The web file header's link actions (`FileHeaderLinks.vue`): copy file path, copy the
 * GitHub link scrolled to this file, and view the file on GitHub at head (base for a
 * removed file). On phones the web app folds them behind a "more" button; so does this.
 */
@Composable
internal fun FileLinksMenu(target: OpenTarget, path: String, opened: OpenedReview?) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val repo = FileLinks.repoOf(target)
    val file = opened?.files?.firstOrNull { it.path == path }
    val sha = if (file?.status == "removed") opened.baseSha else opened?.headSha.orEmpty()
    val blob = if (repo != null && sha.isNotBlank()) FileLinks.blob(repo.first, repo.second, sha, path) else null
    val diffUrl = opened?.url.orEmpty()
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("•••") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Copy file path") }, onClick = {
                copy(context, path)
                open = false
            })
            if (repo != null && diffUrl.isNotBlank()) {
                DropdownMenuItem(text = { Text("Copy GitHub link") }, onClick = {
                    copy(context, FileLinks.fileDiff(diffUrl, target is OpenTarget.Pull, path))
                    open = false
                })
            }
            if (blob != null) {
                DropdownMenuItem(text = { Text("View file on GitHub") }, onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(blob)))
                    open = false
                })
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("pulls.review", text))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
