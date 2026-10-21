package com.octaviantocan.pulls.core.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Goldens captured from the unchanged CommonMark 0.24.0 parser and extension configuration. */
class MarkdownGoldenTest {
    @Test fun inlineFormattingAndFullHtml5Entities() = check("inline")
    @Test fun headingsAndRules() = check("headings")
    @Test fun hardAndSoftLineBreaks() = check("line-breaks")
    @Test fun gfmTablesAlignEscapedPipesAndShortRows() = check("tables")
    @Test fun tasksNestedListsAndOrderedStarts() = check("lists")
    @Test fun fencedAndIndentedCodePreserveContent() = check("code")
    @Test fun quotesAndNestedCode() = check("quotes")
    @Test fun linksReferenceDefinitionsAndLinkedImages() = check("links-images")
    @Test fun collapsedShortcutAndMissingReferenceLinksAndImages() = check("reference-forms")
    @Test fun referenceLabelsEntitiesAndUndefinedFallbacks() = check("reference-edge")
    @Test fun bareAutolinksIncludeEmailAndPreservePunctuation() = check("autolinks")
    @Test fun htmlRemainsInertRendererInput() = check("html")
    @Test fun mentionTextAndCodeRemainDistinct() = check("mentions")
    @Test fun mathSyntaxDoesNotChangeExistingLiteralText() = check("literal-math")

    private fun check(id: String) {
        val fixture = markdownGoldens.first { it.s("id") == id }
        assertEquals(fixture["tree"], snapshot(parseMarkdown(fixture.s("source"))), id)
    }
}

internal val markdownGoldens: List<JsonObject> get() = Json.parseToJsonElement(MarkdownFixtures.json).jsonArray.map { it.jsonObject }
internal fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content

internal fun snapshot(node: Node): JsonObject = buildJsonObject {
    put("type", checkNotNull(node::class.simpleName))
    when (node) {
        is Text -> put("literal", node.literal)
        is Code -> put("literal", node.literal)
        is FencedCodeBlock -> { put("literal", node.literal); put("info", node.info) }
        is IndentedCodeBlock -> put("literal", node.literal)
        is HtmlInline -> put("literal", node.literal)
        is HtmlBlock -> put("literal", node.literal)
        is Heading -> put("level", node.level)
        is OrderedList -> put("start", node.markerStartNumber)
        is TaskListItemMarker -> put("checked", node.isChecked)
        is TableCell -> node.alignment?.let { put("alignment", it.name) }
        is Link -> { put("destination", node.destination); node.title?.let { put("title", it) } }
        is Image -> { put("destination", node.destination); node.title?.let { put("title", it) } }
        else -> Unit
    }
    put("children", JsonArray(node.children.map(::snapshot)))
}
