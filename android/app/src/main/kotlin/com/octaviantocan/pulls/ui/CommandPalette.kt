package com.octaviantocan.pulls.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalSheet
import com.octaviantocan.pulls.ui.design.PillInput
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.t

private const val LIMIT = 200

/** How many commands each group shows until it is asked for more or a search narrows the list. */
private const val PER_GROUP = 4

/**
 * Commands: a sheet like the rest of the app. A search pill, then the matching commands in white
 * cards, one for each group, as plain rows with no icons. Until you search, each group shows only
 * its first few and says how many more there are, so the list stays short; Go on the keyboard runs
 * the first match. It opens at its tall height and its search takes the keyboard as soon as it rests (J11).
 */
@Composable
fun CommandPalette() {
    if (!Commands.paletteOpen) return
    // Pulled down from the Inbox, it comes down from the top with the finger; opened any other way it rises like every sheet.
    // It opens at its tall height with the search taking the keyboard, so typing starts at once (J11).
    PullsSheet(Commands::close, "Commands", full = true, tall = true) {
        CommandsPage(Commands.scope, onClearScope = { Commands.scope = null }) { command ->
            Commands.close()
            command.run()
        }
    }
}

/**
 * What Commands holds, for its own sheet and for the page of it inside the profile sheet: the search, the [scope]
 * chip (a tap clears it with [onClearScope]) and the groups of commands. [onRun] closes what has to close and runs one.
 */
@Composable
internal fun ColumnScope.CommandsPage(scope: PaletteScope?, onClearScope: () -> Unit, onRun: (Command) -> Unit) {
    val c = LocalPullsColors.current
    val general = t("palette.group.general")
    var query by remember { mutableStateOf("") }
    val candidates = remember(scope) { Commands.all().filter { !it.hidden && (scope == null || it.id.startsWith(scope.prefix)) } }
    val ranked = remember(candidates, query) { CommandRank.rankItems(candidates, query) { "${it.title} ${it.keywords.orEmpty()} ${it.group.orEmpty()}" } }
    val results = ranked.take(LIMIT)
    val sections = remember(results) { results.groupBy { it.group ?: general }.toList() }
    val first = sections.firstOrNull()?.second?.firstOrNull()
    val expanded = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    // The keyboard comes with the tall height: lifted there by a drag, the sheet focuses the search once it rests, and a
    // tap on the search lifts it. With a hardware keyboard nothing pops up over the sheet, so the search takes typing at once.
    val sheet = LocalSheet.current
    val hardKeys = LocalConfiguration.current.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val ready = sheet == null || (sheet.settled && (sheet.tall || hardKeys))
    LaunchedEffect(ready) { if (ready) runCatching { focus.requestFocus() } }

    fun run(command: Command?) {
        if (command != null) onRun(command)
    }

    PillInput(
        query, { query = it }, t("palette.placeholder"),
        Modifier.padding(top = 14.dp).focusRequester(focus).onFocusChanged { if (it.hasFocus) sheet?.lift() },
        imeAction = ImeAction.Go, onSend = { run(first) },
    )
    if (scope != null) {
        Row(Modifier.padding(top = 12.dp)) { SoftChip(scope.label, selected = true, onClick = onClearScope) }
    }
    if (results.isEmpty()) {
        Text(t("palette.empty"), Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    sections.forEach { (name, entries) ->
        val all = query.isNotBlank() || name in expanded
        val shown = if (all) entries else entries.take(PER_GROUP)
        Text(name, Modifier.padding(start = 16.dp, top = 24.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        SheetCard {
            shown.forEachIndexed { index, command -> CommandRow(command.title, first = index == 0) { run(command) } }
            if (shown.size < entries.size) CommandRow("${entries.size - shown.size} more", first = false, muted = true) { expanded.add(name) }
        }
    }
    if (ranked.size > LIMIT) {
        Text(LocalMessages.current.t("palette.more", "n" to ranked.size - LIMIT), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.muted)
    }
}

/** One command: its whole title, wrapping rather than cut. */
@Composable
private fun CommandRow(title: String, first: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .cueClickable(role = Role.Button, onClick = onClick)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, style = PullsType.row, color = if (muted) c.muted else c.text)
    }
}
