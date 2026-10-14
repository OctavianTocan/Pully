package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import kotlinx.coroutines.launch

/**
 * Edits the pull request's description in Markdown, as GitHub holds it. Saving sends it through the server's GitHub
 * token and then reloads the overview, so the description on the page is GitHub's own rendering of what was typed.
 */
@Composable
internal fun DescriptionEditSheet(session: ReviewSession, overview: PrOverview, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var text by rememberSaveable(overview.id) { mutableStateOf(overview.body) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = text != overview.body

    fun save() {
        if (busy || !changed) return
        busy = true
        error = null
        scope.launch {
            runCatching { session.repo.prAction(overview.id, "edit", body = text) }
                .onSuccess {
                    session.reloadOverview()
                    toaster.show("Description updated")
                    onClose()
                }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    PullsSheet(
        onClose, "Edit description", full = true,
        footer = {
            error?.let { Text(it, Modifier.fillMaxWidth().padding(bottom = 8.dp), style = PullsType.sm, color = c.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", onClose, Modifier.weight(1f), enabled = !busy)
                CtaButton("Save", { save() }, Modifier.weight(1.6f), enabled = changed && !busy, leading = { if (busy) Orb(size = 18.dp) })
            }
        },
    ) {
        SheetCard { SheetField(null, text, { text = it }, placeholder = "Describe the change in Markdown", first = true, enabled = !busy, minLines = 12) }
    }
}
