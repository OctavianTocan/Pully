package com.octaviantocan.pulls.core.diagram

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bundled Pele, run as the phone runs it, draws each kind of diagram into SVG both hosts can read. */
class PeleRenderTest {
    private val palette = DiagramPalette(
        bg = "#fdfcf9", fg = "#0e333a", muted = "#6e8285", line = "#6e8285", surface = "#f4f2ef", surfaceAlt = "#f1f0ec",
        border = "#e3e5e3", accent = "#1e6f78", critical = "#c0392b",
        series = listOf("#3b82f6", "#f97316", "#16a34a", "#dc2626", "#24929c", "#d97706", "#9333ea", "#93a2a4"),
    )

    private val diagrams = mapOf(
        "flowchart" to "flowchart LR\n  A[Open the pull request] --> B{Checks green?}\n  B -->|yes| C[Merge]\n  B -->|no| D[Fix it]\n  D --> B",
        "subgraph" to "flowchart TD\n  subgraph Phone\n    UI[Compose] --> VM[Session]\n  end\n  subgraph Server\n    API --> DB[(Cache)]\n  end\n  VM --> API",
        "sequence" to "sequenceDiagram\n  participant P as Phone\n  participant S as Server\n  P->>S: diff-patches\n  S-->>P: hunks\n  Note over P,S: one request per file",
        "class" to "classDiagram\n  class ReviewFile {\n    +String path\n    +Int additions\n    +hunks() List\n  }\n  ReviewFile <|-- Renamed",
        "state" to "stateDiagram-v2\n  [*] --> Open\n  Open --> Merged\n  Open --> Closed\n  Merged --> [*]",
        "er" to "erDiagram\n  ACCOUNT ||--o{ KEY : makes\n  KEY { string id string name }",
        "gantt" to "gantt\n  dateFormat YYYY-MM-DD\n  section Release\n  Build :a1, 2026-10-01, 3d\n  Ship :after a1, 2d",
        "pie" to "pie title Files\n  \"Kotlin\" : 42\n  \"Rust\" : 30\n  \"Docs\" : 8",
        "git" to "gitGraph\n  commit\n  branch feature\n  commit\n  checkout main\n  merge feature",
        "mindmap" to "mindmap\n  root((Pully))\n    Inbox\n    Review\n      Diff\n      Tour",
        "journey" to "journey\n  title Review\n  section Read\n    Open: 5: Tavi\n    Comment: 3: Tavi",
        "xychart" to "xychart-beta\n  title Reviews\n  x-axis [Mon, Tue, Wed]\n  y-axis 0 --> 10\n  bar [3, 7, 5]",
    )

    private fun render(js: QuickJs, source: String, width: Int = 400): Map<String, String> = runBlocking {
        Json.parseToJsonElement(js.evaluate<String>(peleRenderScript(source, width))).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun everyKindOfDiagramDrawsAsInertVectors() = runBlocking {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.evaluate<Any?>(File("../shared-ui/pele/pele.global.js").readText(), "pele.js")
            val out = System.getenv("PELE_SVG_OUT")?.let(::File)?.apply { mkdirs() }
            for ((name, source) in diagrams) {
                val result = render(js, source)
                val svg = result["svg"] ?: error("$name: ${result["error"]}")
                val inert = inertPeleSvg(svg, palette)
                for (gone in listOf("var(", "color-mix", "url(", "style=", "class=", "<a", "<image", "<foreignObject", "currentColor")) {
                    assertFalse(gone in inert, "$name keeps $gone")
                }
                val tags = Regex("<([a-zA-Z]+)").findAll(inert).map { it.groupValues[1] }.toSet()
                assertTrue(tags.all { it in setOf("svg", "g", "path", "rect", "circle", "ellipse", "polygon", "polyline", "line", "text", "tspan") }, "$name: $tags")
                assertTrue("<text" in inert, "$name has no text")
                out?.let { File(it, "$name.svg").writeText(svg) }
            }
        } finally { js.close() }
    }

    @Test
    fun brokenTextSaysWhyAndScriptStaysText() = runBlocking {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.evaluate<Any?>(File("../shared-ui/pele/pele.global.js").readText(), "pele.js")
            assertTrue(render(js, "flowchart LR\n  A -->")["error"].orEmpty().isNotEmpty())
            assertTrue(render(js, "not a diagram")["error"].orEmpty().isNotEmpty())
            // A label that looks like markup or script is drawn as its words.
            val svg = render(js, "flowchart LR\n  A[\"<script>alert(1)</script>\"] --> B[\"\\\"); globalThis.x = 1; (\\\"\"]")["svg"].orEmpty()
            assertFalse("<script" in svg)
            assertEquals("undefined", js.evaluate<String>("typeof globalThis.x"))
        } finally { js.close() }
    }
}
