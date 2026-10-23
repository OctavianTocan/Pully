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
