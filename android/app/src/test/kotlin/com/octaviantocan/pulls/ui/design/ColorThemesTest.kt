package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The colour themes (K18): Pulls by default, and every theme a whole, readable palette in both looks. */
class ColorThemesTest {
    /** Looks whose text is under 4.5:1 on their screen as OpenCode draws them, kept rather than repainted. */
    private val lowContrast = setOf("solarized/light")

    private fun looks() = ColorThemes.all.flatMap { theme -> listOf("${theme.id}/light" to theme.colors(false), "${theme.id}/dark" to theme.colors(true)) }

    private fun contrast(x: Color, y: Color): Float {
        val (hi, lo) = listOf(x.luminance(), y.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun pullsIsTheDefault() {
        assertSame(ColorThemes.Pulls, ColorThemes.all.first())
        assertEquals(ColorThemes.DEFAULT, ColorThemes.Pulls.id)
        assertSame(ColorThemes.Pulls, ColorThemes.of(null))
        assertSame(ColorThemes.Pulls, ColorThemes.of("a-theme-that-was-dropped"))
        assertSame(LightPullsColors, ColorThemes.Pulls.colors(false))
        assertSame(DarkPullsColors, ColorThemes.Pulls.colors(true))
    }

    @Test
    fun everyThemeIsListedOnceWithAName() {
        val ids = ColorThemes.all.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(ColorThemes.all.size > 30, "OpenCode ships 32 opaque themes; ${ColorThemes.all.size - 1} were generated")
        ColorThemes.all.forEach { assertTrue(it.name.isNotBlank(), "${it.id} has no name") }
        assertSame(ColorThemes.of("tokyonight"), ColorThemes.all.single { it.name == "Tokyo Night" })
    }

    @Test
    fun aThemeWithOneLookUsesItForBoth() {
        val aura = ColorThemes.of("aura")
        assertEquals(null, aura.dark)
        assertSame(aura.light, aura.colors(true))
        assertTrue(aura.colors(false).dark, "Aura is dark under Light too")
    }

    @Test
    fun everyRoleIsAColourAndTheSurfacesAndInkAreOpaque() {
        looks().forEach { (look, c) ->
            val roles = PullsColors::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType && !it.name.contains('$') }
            assertTrue(roles.size >= 40, "PullsColors has ${roles.size} colour roles")
            roles.forEach { field ->
                field.isAccessible = true
                assertTrue(Color(field.getLong(c).toULong()) != Color.Unspecified, "$look has no ${field.name}")
            }
            listOf(c.base, c.text, c.muted, c.faint, c.border, c.raised, c.secondary, c.sunken, c.code, c.primary, c.success, c.error, c.warning, c.diffAdd, c.diffDel, c.cta, c.onCta)
                .forEach { assertEquals(1f, it.alpha, "$look has a see-through surface or ink") }
        }
    }

    @Test
    fun darkSaysWhichLookAPaletteIs() {
        looks().forEach { (look, c) -> assertEquals(c.base.luminance() < c.text.luminance(), c.dark, "$look") }
    }

    @Test
    fun textReadsOnTheScreenInBothLooks() {
        val low = looks().filter { (_, c) -> contrast(c.text, c.base) < 4.5f }.map { it.first }.toSet()
        assertEquals(lowContrast, low, "text under 4.5:1 on base")
        looks().forEach { (look, c) -> assertTrue(contrast(c.text, c.base) >= 4f, "$look text is at ${contrast(c.text, c.base)}:1") }
    }

    @Test
    fun whiteInkReadsOnThePrimaryFill() {
        // The send bubble and the swipe to open draw white on `primary` in every theme.
        looks().forEach { (look, c) -> assertTrue(contrast(Color.White, c.primary) >= 3f, "$look primary is at ${contrast(Color.White, c.primary)}:1 under white") }
    }
}
