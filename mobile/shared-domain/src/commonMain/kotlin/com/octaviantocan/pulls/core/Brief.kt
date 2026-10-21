package com.octaviantocan.pulls.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The brief: a page the agent designs for one pull request in OpenUI Lang. The server parses the Lang with
 * OpenUI's own parser against the component library in `packages/core/src/analyze/adapters/llm/brief.ts` and
 * sends the phone a plain `{ type, props }` tree with children resolved. This file turns that tree into
 * typed blocks the screen draws natively. It is the one place that knows the library's component names, so
 * adding a component means one case here and one in the renderer; a name it does not know never renders.
 */

enum class CalloutTone { Info, Good, Warning, Risk }

enum class FindingSeverity { Bug, Risk, Nit }

/** A path in the diff, optionally a line in the new file. A block that carries one opens the diff there. */
data class BriefTarget(val path: String, val line: Int? = null)

data class BriefCheck(val text: String, val target: BriefTarget? = null)

data class BriefStep(val title: String, val text: String)

data class BriefFile(val target: BriefTarget, val note: String)

sealed interface BriefBlock {
    data class Text(val markdown: String) : BriefBlock

    data class Callout(val tone: CalloutTone, val text: String, val title: String? = null) : BriefBlock

    data class KeyValues(val rows: List<Pair<String, String>>) : BriefBlock

    data class Table(val columns: List<String>, val rows: List<List<String>>) : BriefBlock

    data class Checklist(val items: List<BriefCheck>) : BriefBlock

    data class Steps(val items: List<BriefStep>) : BriefBlock

    data class FileList(val files: List<BriefFile>) : BriefBlock

    data class Diagram(val mermaid: String, val caption: String? = null) : BriefBlock

    data class Code(val code: String, val language: String? = null, val caption: String? = null) : BriefBlock

    data class BeforeAfter(val before: String, val after: String, val title: String? = null) : BriefBlock

    data class Finding(val severity: FindingSeverity, val title: String, val text: String, val target: BriefTarget? = null) : BriefBlock

    data class Section(val title: String, val open: Boolean, val blocks: List<BriefBlock>) : BriefBlock
}

/** The table as GitHub-flavoured Markdown, so the app's Markdown view draws it: a pipe or a line break in a cell cannot split it. */
fun BriefBlock.Table.markdown(): String {
    fun row(cells: List<String>) = cells.joinToString(" | ", prefix = "| ", postfix = " |") { cell -> cell.replace("|", "\\|").replace(Regex("\\s*\\n\\s*"), " ").trim() }
    return (listOf(row(columns), row(columns.map { "---" })) + rows.map(::row)).joinToString("\n")
}

/** The page: its blocks from top to bottom. Never empty. */
data class BriefPage(val blocks: List<BriefBlock>)

/**
 * Reads the tree `review-state` carries as `brief.tree`.
 * @return the page, or `null` when the tree is not a `Stack` or holds nothing that can be drawn.
 */
fun parseBriefPage(tree: JsonElement?): BriefPage? {
    val root = tree as? JsonObject ?: return null
    if (root.type() != "Stack") return null
    val blocks = root.props().list("children").mapNotNull(::blockOf)
    return blocks.takeIf { it.isNotEmpty() }?.let(::BriefPage)
}

private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.props(): JsonObject = this["props"] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.line(key: String): Int? = (this[key] as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { it > 0 }

private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull.orEmpty() }

private fun targetOf(props: JsonObject): BriefTarget? = props.text("path")?.let { BriefTarget(it, props.line("line")) }

private fun blockOf(node: JsonObject): BriefBlock? {
    val props = node.props()
    return when (node.type()) {
        "Text" -> props.text("markdown")?.let(BriefBlock::Text)
        "Callout" -> props.text("text")?.let { text ->
            BriefBlock.Callout(
                tone = when (props.text("tone")) {
                    "risk" -> CalloutTone.Risk
                    "warning" -> CalloutTone.Warning
                    "good" -> CalloutTone.Good
                    else -> CalloutTone.Info
                },
                text = text,
                title = props.text("title"),
            )
        }
        "KeyValues" -> props.list("rows").mapNotNull { row ->
            val label = row.props().text("label") ?: return@mapNotNull null
            label to row.props().text("value").orEmpty()
        }.takeIf { it.isNotEmpty() }?.let(BriefBlock::KeyValues)
        "Table" -> {
            val columns = props.strings("columns")
            val rows = (props["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
                (row as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            }
            // A grid needs a header and at least one row; short rows are padded so every row has a cell per column.
            if (columns.isEmpty() || rows.isEmpty()) null
            else BriefBlock.Table(columns, rows.map { row -> List(columns.size) { index -> row.getOrElse(index) { "" } } })
        }
        "Checklist" -> props.list("items").mapNotNull { item ->
            val p = item.props()
            p.text("text")?.let { BriefCheck(it, targetOf(p)) }
        }.takeIf { it.isNotEmpty() }?.let(BriefBlock::Checklist)
        "Steps" -> props.list("items").mapNotNull { item ->
            val p = item.props()
            val title = p.text("title") ?: return@mapNotNull null
            BriefStep(title, p.text("text").orEmpty())
        }.takeIf { it.isNotEmpty() }?.let(BriefBlock::Steps)
        "FileList" -> props.list("files").mapNotNull { entry ->
            val p = entry.props()
            targetOf(p)?.let { BriefFile(it, p.text("note").orEmpty()) }
        }.takeIf { it.isNotEmpty() }?.let(BriefBlock::FileList)
        "Diagram" -> props.text("mermaid")?.let { BriefBlock.Diagram(it, props.text("caption")) }
        "Code" -> props.text("code")?.let { BriefBlock.Code(it, props.text("language"), props.text("caption")) }
        "BeforeAfter" -> {
            val before = props.text("before")
            val after = props.text("after")
            if (before == null && after == null) null
            else BriefBlock.BeforeAfter(before.orEmpty(), after.orEmpty(), props.text("title"))
        }
        "Finding" -> {
            val title = props.text("title") ?: return null
            BriefBlock.Finding(
                severity = when (props.text("severity")) {
                    "bug" -> FindingSeverity.Bug
                    "risk" -> FindingSeverity.Risk
                    else -> FindingSeverity.Nit
                },
                title = title,
                text = props.text("text").orEmpty(),
                target = targetOf(props),
            )
        }
        "Section" -> {
            val title = props.text("title") ?: return null
            // A section holds blocks only; the library forbids a section inside a section, and so does this.
            val blocks = props.list("children").filter { it.type() != "Section" }.mapNotNull(::blockOf)
            val open = (props["open"] as? JsonPrimitive)?.booleanOrNull ?: true
            blocks.takeIf { it.isNotEmpty() }?.let { BriefBlock.Section(title, open, it) }
        }
        else -> null
    }
}
