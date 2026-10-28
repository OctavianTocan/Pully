package com.octaviantocan.pulls.core.diagram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeleSvgTest {
    private val palette = DiagramPalette(
        bg = "#ffffff", fg = "#262626", muted = "#525252", line = "#a3a3a3", surface = "#f6f6f6", surfaceAlt = "#eeeeee",
        border = "#d4d4d4", accent = "#49833e", critical = "#d14d41", series = listOf("#4c78a8", "#f58518"),
    )

    // Pele 1.12.0's own output for `flowchart LR; A[Mermaid text] --> B{Valid?}; B -->|yes| C[SVG]`, shortened.
    private val flowchart = """<svg xmlns="http://www.w3.org/2000/svg" class="pele pele-flowchart" viewBox="0 0 458 152" width="458" height="152" style="max-width:100%;height:auto;--_bg:var(--pele-bg,#fff);--_fg:var(--pele-fg,#1f1f1f);--_l:var(--pele-line,#1f1f1f);--_s:var(--pele-surface,#f3f3f3);--_a:var(--pele-surface-alt,#e6e6e6);--_b:var(--pele-border,#8a8a8a);color:var(--_fg);--_r:var(--pele-radius,4px);--_w:var(--pele-bold-weight,bold);--_fm:var(--pele-font-mono,monospace);font:16px sans-serif;letter-spacing:normal;direction:ltr;font-family:var(--pele-font,sans-serif)" fill="var(--_fg)" role="graphics-document document" aria-roledescription="flowchart"><g class="pele-edges" fill="none" stroke="var(--_l)" stroke-linecap="round"><g class="pele-edge" data-id="L_A_B_0"><path d="M136.46,76L177.46,76"/><path class="pele-marker" d="M184.46,76L176.46,79.36L176.46,72.64Z" fill="var(--_l)" stroke="none"/></g></g><g class="pele-edge-labels" font-size="14"><g class="pele-edge-label" data-id="L_B_C_0"><rect x="318.63" y="21.5" width="29.78" height="21" rx="3" fill="var(--_bg)"/><text x="333.52" y="36.9" text-anchor="middle">yes &amp; no</text></g></g><g class="pele-nodes"><g class="pele-node pele-shape-rect" data-id="A" transform="translate(72.23,76)"><rect x="-64.23" y="-24" width="128.46" height="48" fill="var(--_s)" stroke="var(--_b)" style="rx:var(--_r)"/><text class="pele-label" x="0" y="5.6" text-anchor="middle">Mermaid text</text></g><g class="pele-cluster"><rect width="10" height="10" fill="color-mix(in srgb,var(--_a) 50%,var(--_bg))"/><text font-family="var(--_fm)" font-weight="var(--_w)" font-size="smaller">a<tspan fill="currentColor">b</tspan></text></g></g></svg>"""

    @Test
    fun peleSvgBecomesPlainPaintedShapes() {
        val out = inertPeleSvg(flowchart, palette)
        for (gone in listOf("var(", "color-mix", "currentColor", "style=", "class=", "data-id", "role=", "aria-")) {
            assertFalse(gone in out, "$gone left in $out")
        }
        assertTrue(out.startsWith("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 458 152" width="458" height="152" fill="#262626" font-family="sans-serif" font-size="16">"""), out)
        assertTrue("""stroke="#a3a3a3"""" in out)
        assertTrue("""<rect x="-64.23" y="-24" width="128.46" height="48" fill="#f6f6f6" stroke="#d4d4d4" rx="4">""" in out, out)
        assertTrue(">yes &amp; no</text>" in out)
        // Half the group colour into the background, as `color-mix` gives it.
        assertTrue("""fill="#f7f7f7"""" in out, out)
        assertTrue("""<text font-family="monospace" font-weight="bold" font-size="13.33">a<tspan fill="#262626">b</tspan></text>""" in out, out)
    }

    @Test
    fun aLinkIsAGroupAndAPictureIsLeftOut() {
        val out = inertPeleSvg("""<svg viewBox="0 0 10 10"><a href="https://x.test"><text>t</text></a><image href="https://x.test/a.png"/><foreignObject><div>h</div></foreignObject></svg>""", palette)
        assertEquals("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><g><text>t</text></g></svg>""", out)
    }

    @Test
    fun aGradientGivesWayToItsColour() {
        val out = inertPeleSvg("""<svg viewBox="0 0 1 1"><path d="M0 0" fill="url(#g) var(--pele-series-2,#000)"/></svg>""", palette)
        assertTrue("""fill="#f58518"""" in out, out)
    }

    @Test
    fun anythingButWellFormedSvgFails() {
        assertFailsWith<IllegalArgumentException> { inertPeleSvg("<svg><g></svg>", palette) }
        assertFailsWith<IllegalArgumentException> { inertPeleSvg("<html></html>", palette) }
    }

    @Test
    fun theDiagramTextIsAStringNeverCode() {
        val script = peleRenderScript("graph TD\n  A[\"x\"] --> B\n'); evil(); ('", 320)
        assertTrue("""pele.render("graph TD\n  A[\"x\"] --> B\n'); evil(); ('",{maxWidth:320,links:false,images:false""" in script, script)
    }
}
