package com.octaviantocan.pulls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.AppModal
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t

private const val LIMIT = 200

/**
 * Port of `CommandPalette.vue`: a search field (with the scope chip), then the matching
 * commands grouped by section (`text-xs color-faint font-medium` headings), each row an icon
 * and a title; the first result is selected and Enter runs it. The keyboard key hints of the
 * web footer are left out, as the web does on touch (`coarse:hidden`).
 */
@Composable
fun CommandPalette() {
    if (!Commands.paletteOpen) return
    val c = LocalPullsColors.current
    val general = t("palette.group.general")
    var query by remember { mutableStateOf("") }
    val scope = Commands.scope
    val candidates = remember(scope) { Commands.all().filter { !it.hidden && (scope == null || it.id.startsWith(scope.prefix)) } }
    val ranked = remember(candidates, query) { CommandRank.rankItems(candidates, query) { "${it.title} ${it.keywords.orEmpty()} ${it.group.orEmpty()}" } }
    val results = ranked.take(LIMIT)
    val sections = remember(results) { results.groupBy { it.group ?: general }.toList() }
    val first = sections.firstOrNull()?.second?.firstOrNull()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun run(command: Command?) {
        if (command == null) return
        Commands.close()
        command.run()
    }

    AppModal(onClose = Commands::close, scrollBody = false, bodyPadding = false) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon("magnifying-glass-duotone", tint = c.mute, size = 18.dp)
            if (scope != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(c.active).border(1.dp, c.border, RoundedCornerShape(4.dp))
                        .clickable(onClickLabel = t("palette.clearScope")) { Commands.scope = null }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(scope.label, style = PullsType.xs, color = c.text)
                    PhIcon("x", tint = c.mute, size = 10.dp)
                }
            }
            BasicTextField(
                query, { query = it },
                Modifier.weight(1f).focusRequester(focus).defaultMinSize(minHeight = 36.dp),
                singleLine = true,
                textStyle = PullsType.sm.copy(color = c.text),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { run(first) }),
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(t("palette.placeholder"), style = PullsType.sm, color = c.mute, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        androidx.compose.material3.HorizontalDivider(color = c.border)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 448.dp).padding(vertical = 4.dp)) {
            if (results.isEmpty()) item {
                Text(t("palette.empty"), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp), style = PullsType.sm, color = c.fade, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            sections.forEach { (name, entries) ->
                item(key = "h:$name") {
                    Text(name, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = c.faint)
                }
                items(entries, key = { "c:${it.id}" }) { command ->
                    val selected = command === first
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp).defaultMinSize(minHeight = 44.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (selected) c.active else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(role = Role.Button) { run(command) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val fg = if (selected) c.text else c.fade
                        PhIcon(command.icon ?: "dot-outline", tint = fg, size = 16.dp)
                        Text(command.title, Modifier.weight(1f), style = PullsType.sm, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (ranked.size > LIMIT) item {
                Text(LocalMessages.current.t("palette.more", "n" to ranked.size - LIMIT), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = PullsType.xs, color = c.mute)
            }
        }
    }
}
