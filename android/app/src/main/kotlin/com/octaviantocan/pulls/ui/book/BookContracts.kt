package com.octaviantocan.pulls.ui.book

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.octaviantocan.pulls.core.Contract
import com.octaviantocan.pulls.core.ContractsFile
import com.octaviantocan.pulls.core.contractArea
import com.octaviantocan.pulls.core.parseContractBundle
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetSearch
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.matchesSearch
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.sideScrollFade
import com.octaviantocan.pulls.ui.home.GroupHeading

/** The labels a contract may carry, in the order the filter shows them, each with the word the page writes. */
private val LABEL_WORDS = linkedMapOf("product" to "Product", "design" to "Design", "motion" to "Motion", "architecture" to "Architecture", "security" to "Security")

internal val CONTRACT_LABELS: List<String> = LABEL_WORDS.keys.toList()

/** One area of the code and its contracts, each with the file it is in. */
internal class ContractArea(val name: String, val rank: Int, val files: List<String>, val contracts: List<Pair<String, Contract>>)

/** `contracts.txt`, the bundle the build writes from every `CONTRACTS` file of the repository. */
internal fun loadContracts(context: Context): List<ContractsFile> =
    runCatching { context.assets.open("contracts.txt").bufferedReader().use { parseContractBundle(it.readText()) } }.getOrDefault(emptyList())

/** The files grouped by the area of the code they govern, in the book's order. */
internal fun contractAreas(files: List<ContractsFile>): List<ContractArea> =
    files.groupBy { contractArea(it.directory) }
        .map { (area, inArea) -> ContractArea(area.first, area.second, inArea.map { it.path }, inArea.flatMap { f -> f.contracts.map { f.path to it } }) }
        .sortedWith(compareBy({ it.rank }, { it.name }))

/** Whether [contract] shows under [label] (null is All) and [query] (every word in its ID, labels, area or prose). */
internal fun contractShown(contract: Contract, area: String, label: String?, query: String): Boolean =
    (label == null || label in contract.labels) &&
        (query.isBlank() || matchesSearch("${contract.id} ${contract.labels.joinToString(" ")} $area ${contract.prose}", query))

/**
 * Every code contract of the repository, read from the bundle the build ships: a fold per area of
 * the code, named in plain words, a label filter and a search. Areas start folded, as a long list
 * does (rule 101), and a search or a label opens every area it finds something in.
 */
@Composable
internal fun ContractsPage() {
    val context = LocalContext.current
    val areas = remember { contractAreas(loadContracts(context)) }
    val total = areas.sumOf { it.contracts.size }
    var query by rememberSaveable { mutableStateOf("") }
    var labelIndex by rememberSaveable { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val label = CONTRACT_LABELS.getOrNull(labelIndex - 1)
    val narrowed = query.isNotBlank() || label != null
    val motion = rememberMotionEnabled()
    Column(Modifier.fillMaxWidth()) {
        PageColumn {
            PageTitle("Code contracts", "What the code must keep, from Tavi's recordings and DESIGN.md: $total contracts in ${areas.sumOf { it.files.size }} files.")
            SheetSearch(query, { query = it }, "Find a contract")
            val scroll = rememberScrollState()
            Row(
                Modifier.fillMaxWidth().sideScrollFade(scroll).horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2),
            ) {
                SoftChip("All", labelIndex == 0, { labelIndex = 0 }, count = "$total")
                CONTRACT_LABELS.forEachIndexed { index, name ->
                    val count = areas.sumOf { a -> a.contracts.count { name in it.second.labels } }
                    SoftChip(LABEL_WORDS.getValue(name), labelIndex == index + 1, { labelIndex = index + 1 }, count = "$count")
                }
            }
            if (areas.isEmpty()) BodyText("This build has no contracts bundled.", muted = true)
        }
        var first = true
        areas.forEach { area ->
            val shown = area.contracts.filter { contractShown(it.second, area.name, label, query) }
            if (shown.isEmpty()) return@forEach
            val folded = !narrowed && area.name !in open
            GroupHeading(
                area.name,
                shown.size,
                first = first,
                folded = folded,
                onFold = if (narrowed) null else ({ open = if (area.name in open) open - area.name else open + area.name }),
                onOpen = null,
            )
            first = false
            AnimatedVisibility(
                !folded,
                enter = if (motion) fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandVertically(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) else EnterTransition.None,
                exit = if (motion) fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + shrinkVertically(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) else ExitTransition.None,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = PullsSpace.x4).padding(bottom = PullsSpace.x4)) {
                    area.files.forEach { Mono(it, muted = true, modifier = Modifier.padding(top = PullsSpace.x1)) }
                    shown.forEach { (_, contract) -> ContractCard(contract) }
                }
            }
        }
        if (areas.isNotEmpty() && areas.none { a -> a.contracts.any { contractShown(it.second, a.name, label, query) } }) {
            PageColumn { BodyText("No contract matches.", muted = true) }
        }
    }
}

/** One contract: its ID in the code font, its labels as small pills, and its prose as Markdown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContractCard(contract: Contract) {
    val c = LocalPullsColors.current
    SheetCard {
        Column(Modifier.fillMaxWidth().padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
            Text(contract.id, style = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium), color = c.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PullsSpace.x1), verticalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
                contract.labels.forEach { LabelPill(it) }
            }
            Markdown(contract.prose, style = PullsType.read)
        }
    }
}

/** A label as a small grey pill, drawn like the model picker's Recommended pill. */
@Composable
private fun LabelPill(label: String) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.full)
    Box(Modifier.clip(shape).background(c.sunken).padding(horizontal = PullsSpace.x2)) {
        Text(LABEL_WORDS[label] ?: label, style = PullsType.sm, color = c.muted, maxLines = 1)
    }
}
