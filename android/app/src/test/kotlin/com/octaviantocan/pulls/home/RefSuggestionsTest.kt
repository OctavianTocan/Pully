package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.ui.home.refSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals

class RefSuggestionsTest {
    private val names = listOf("main", "feature/login", "feature/logout", "v1.0", "v1.1")

    @Test
    fun `a blank field offers the first refs so they can be browsed`() {
        assertEquals(listOf("main", "feature/login", "feature/logout"), refSuggestions("", names, limit = 3))
        assertEquals(listOf("main", "feature/login"), refSuggestions("  ", names, limit = 2))
    }

    @Test
    fun `typed text keeps the refs that contain it, in any case`() {
        assertEquals(listOf("feature/login", "feature/logout"), refSuggestions("LOG", names))
    }

    @Test
    fun `a ref that is already typed in full offers nothing`() {
        assertEquals(emptyList(), refSuggestions("main", names))
    }
}
