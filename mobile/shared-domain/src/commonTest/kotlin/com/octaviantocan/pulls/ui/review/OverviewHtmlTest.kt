package com.octaviantocan.pulls.ui.review

import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewHtmlTest {
    @Test
    fun numericEntitiesKeepAstralTextAndMalformedValuesStayLiteral() {
        assertEquals("café 🦉", decodeEntities("caf&#233; &#x1F989;"))
        assertEquals("&#x110000; &#999999999999;", decodeEntities("&#x110000; &#999999999999;"))
        assertEquals("\\&unknown;", htmlToMarkdown("<p>&unknown;</p>"))
    }
}

class GithubAlertHtmlTest {
    @Test
    fun anAlertIsAQuoteWithItsTitleAndAHiddenLoaderSaysNothing() {
        val html = """<div class="markdown-alert markdown-alert-caution" dir="auto"><p class="markdown-alert-title" dir="auto"><svg><path d="M0"></path></svg>Caution</p>
<p dir="auto">The pull request is closed.</p>
</div><section><pre lang="mermaid">graph TD</pre><span class="js-render-enrichment-loader"><span class="sr-only">Loading</span></span></section>"""
        assertEquals("> **Caution**\n>\n> The pull request is closed.\n\n```\ngraph TD\n```", htmlToMarkdown(html))
    }
}
