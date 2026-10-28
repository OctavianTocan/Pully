package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.dokar.quickjs.QuickJs
import com.octaviantocan.pulls.core.diagram.DiagramPalette
import com.octaviantocan.pulls.core.diagram.MAX_DIAGRAM_SOURCE
import com.octaviantocan.pulls.core.diagram.inertPeleSvg
import com.octaviantocan.pulls.core.diagram.peleRenderScript
import com.octaviantocan.pulls.ui.ai.DiagramPicture
import com.octaviantocan.pulls.ui.ai.parseDiagramSvg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Draws Mermaid text as Pele's SVG at most `maxWidth` SVG pixels wide, or says why it can't. */
fun interface MermaidRenderer {
    suspend fun render(source: String, maxWidth: Int): Result<String>
}

/** The renderer Markdown draws ```` ```mermaid ```` blocks with; tests put their own here. */
val LocalMermaidRenderer = staticCompositionLocalOf<MermaidRenderer> { PeleEngine }

/**
 * Pele in a QuickJS runtime of its own, loaded on first use and kept: one diagram at a time, off the main thread,
 * with no DOM, network or files, and bounded in memory and time. The last diagrams drawn are kept by text and width.
 */
internal object PeleEngine : MermaidRenderer {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val mutex = Mutex()
    private var runtime: QuickJs? = null
    private val drawn = LinkedHashMap<Pair<String, Int>, Result<String>>()

    fun cached(source: String, maxWidth: Int): Result<String>? = drawn[source to maxWidth]

    override suspend fun render(source: String, maxWidth: Int): Result<String> = withContext(dispatcher) {
        mutex.withLock {
            drawn[source to maxWidth]?.let { return@withLock it }
            val result = runCatching {
                require(source.length <= MAX_DIAGRAM_SOURCE) { "The diagram is too long." }
                val js = runtime ?: QuickJs.create(dispatcher).also {
                    it.memoryLimit = 256L * 1024 * 1024
                    it.evaluationTimeoutMillis = 10_000
                    it.evaluate<Any?>(PeleBundle.code, "pele.js")
                    runtime = it
                }
                val out = Json.parseToJsonElement(js.evaluate<String>(peleRenderScript(source, maxWidth))).jsonObject
                out["svg"]?.jsonPrimitive?.content ?: error(out["error"]?.jsonPrimitive?.content ?: "Pele drew nothing.")
            }
            drawn[source to maxWidth] = result
            if (drawn.size > 48) drawn.remove(drawn.keys.first())
            result
        }
    }
}

/** SVG pixels drawn per dp: Pele's 16px labels come out at the code size. */
private const val DIAGRAM_SCALE = 0.875f

/**
 * A ```` ```mermaid ```` block drawn as its diagram with Pele, laid out for the width it has, in the app's colours and
 * in the existing diagram frame (tap for full screen). While Pele works a skeleton holds the place; when it can't draw
 * the text, [fallback] shows the code as written.
 */
@Composable
fun MermaidDiagram(source: String, fallback: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val renderer = LocalMermaidRenderer.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Widths in steps of 40dp, so a small change of width does not lay the diagram out again.
        val width = ((maxWidth.value - 16f) / DIAGRAM_SCALE / 40f).toInt().coerceAtLeast(6) * 40
        var result by remember(renderer, source, width) {
            mutableStateOf(if (renderer === PeleEngine) PeleEngine.cached(source, width) else null)
        }
        LaunchedEffect(renderer, source, width) { if (result == null) result = renderer.render(source, width) }
        val palette = remember(c) { diagramPalette(c) }
        val svg = remember(result, palette) {
            result?.getOrNull()?.let { text -> runCatching { parseDiagramSvg(inertPeleSvg(text, palette)) }.getOrNull() }
        }
        when {
            svg != null -> DiagramPicture(svg, DIAGRAM_SCALE)
            result == null -> Skeleton(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(PullsRadius.md)))
            else -> fallback()
        }
    }
}

/** Pele's theme in the app's palette, light or dark. */
internal fun diagramPalette(c: PullsColors) = DiagramPalette(
    bg = c.base.hex(), fg = c.text.hex(), muted = c.muted.hex(), line = c.muted.hex(), surface = c.code.hex(),
    surfaceAlt = c.paper2.hex(), border = c.border.hex(), accent = c.primary.hex(), critical = c.error.hex(),
    series = listOf(c.blue, c.orange, c.success, c.error, c.accentTeal, c.amber, c.purple, c.gray).map { it.hex() },
)

private fun Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
