package com.octaviantocan.pulls.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BriefTest {
    private fun parse(json: String) = parseBriefPage(Json.parseToJsonElement(json))

    private fun fixture(): BriefPage {
        return assertNotNull(parse(BriefFixture.json))
    }

    @Test
    fun readsTheTreeTheServerSendsForEveryComponent() {
        val blocks = fixture().blocks
        assertEquals(
            listOf("Callout", "KeyValues", "Table", "Checklist", "Diagram", "Steps", "FileList", "Code", "BeforeAfter", "Finding", "Section"),
            blocks.map { it::class.simpleName },
        )
    }

    @Test
    fun keepsToneTitleAndTheTargetsThatOpenTheDiff() {
        val blocks = fixture().blocks
        val callout = assertIs<BriefBlock.Callout>(blocks[0])
        assertEquals(CalloutTone.Risk, callout.tone)
        assertEquals("Locks on deploy", callout.title)
        val checks = assertIs<BriefBlock.Checklist>(blocks[3])
        assertEquals(BriefCheck("A renamed file is still found", BriefTarget("src/group-utils.ts", 42)), checks.items[0])
        assertNull(checks.items[1].target)
        val files = assertIs<BriefBlock.FileList>(blocks[6])
        assertEquals(BriefFile(BriefTarget("db/migrations/0042_orders.sql", 1), "Adds the column"), files.files[0])
        val finding = assertIs<BriefBlock.Finding>(blocks[9])
        assertEquals(FindingSeverity.Bug, finding.severity)
        assertEquals(BriefTarget("api/orders.ts", 12), finding.target)
    }

    @Test
    fun padsAShortTableRowToOneCellPerColumn() {
        val table = assertIs<BriefBlock.Table>(fixture().blocks[2])
        assertEquals(listOf("total", "integer cents", ""), table.rows[1])
    }

    @Test
    fun aCollapsedSectionKeepsItsBlocks() {
        val section = assertIs<BriefBlock.Section>(fixture().blocks.last())
        assertEquals("Details", section.title)
        assertEquals(false, section.open)
        assertEquals(2, section.blocks.size)
    }

    @Test
    fun sectionsUnfoldByDefaultAndNeverNest() {
        val page = parse(
            """{"type":"Stack","props":{"children":[{"type":"Section","props":{"title":"A","children":[
                {"type":"Text","props":{"markdown":"x"}},
                {"type":"Section","props":{"title":"B","children":[{"type":"Text","props":{"markdown":"y"}}]}}]}}]}}""",
        )
        val section = assertIs<BriefBlock.Section>(page?.blocks?.single())
        assertEquals(true, section.open)
        assertEquals(listOf(BriefBlock.Text("x")), section.blocks)
    }

    @Test
    fun skipsWhatItDoesNotKnowOrCannotDraw() {
        val page = parse(
            """{"type":"Stack","props":{"children":[
                {"type":"Hologram","props":{"text":"x"}},
                {"type":"Text","props":{}},
                {"type":"Table","props":{"columns":[],"rows":[]}},
                {"type":"Checklist","props":{"items":[]}},
                {"type":"FileList","props":{"files":[{"type":"FileEntry","props":{"note":"no path"}}]}},
                {"type":"Callout","props":{"tone":"loud","text":"still shown"}}]}}""",
        )
        assertEquals(listOf<BriefBlock>(BriefBlock.Callout(CalloutTone.Info, "still shown", null)), page?.blocks)
    }

    @Test
    fun anEmptyOrForeignTreeIsNotAPage() {
        assertNull(parse("""{"type":"Stack","props":{"children":[]}}"""))
        assertNull(parse("""{"type":"Section","props":{"title":"x","children":[{"type":"Text","props":{"markdown":"a"}}]}}"""))
        assertNull(parse("""[]"""))
        assertNull(parseBriefPage(null))
    }

    @Test
    fun aLineThatIsNotAPositiveNumberIsDropped() {
        val page = parse("""{"type":"Stack","props":{"children":[{"type":"Finding","props":{"severity":"nit","title":"t","text":"x","path":"a.ts","line":0}}]}}""")
        val finding = assertIs<BriefBlock.Finding>(page?.blocks?.single())
        assertEquals(BriefTarget("a.ts", null), finding.target)
    }

    @Test
    fun aTableBecomesMarkdownWithPipesAndLineBreaksInCellsEscaped() {
        val table = BriefBlock.Table(listOf("Column", "Type"), listOf(listOf("a|b", "line\none"), listOf("`total`", "")))
        assertEquals(
            "| Column | Type |\n| --- | --- |\n| a\\|b | line one |\n| `total` |  |",
            table.markdown(),
        )
    }
}
