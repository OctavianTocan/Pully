package com.octaviantocan.pulls.ui

import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.design.WindowShape
import com.octaviantocan.pulls.ui.design.listPaneWidth
import com.octaviantocan.pulls.ui.design.windowShape
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPanesTest {
    private fun pull(n: Int) = Screen.Review(OpenTarget.Pull("o", "r", n, "github.com"))

    @Test
    fun windowsTakeTheirShapeFromWidthThenHeight() {
        assertEquals(WindowShape.Phone, windowShape(411.dp, 891.dp))
        assertEquals(WindowShape.Phone, windowShape(420.dp, 600.dp))
        assertEquals(WindowShape.Sideways, windowShape(891.dp, 411.dp))
        assertEquals(WindowShape.Sideways, windowShape(960.dp, 540.dp))
        assertEquals(WindowShape.Unfolded, windowShape(841.dp, 701.dp))
        assertEquals(WindowShape.Unfolded, windowShape(707.dp, 841.dp))
        assertEquals(WindowShape.Unfolded, windowShape(1280.dp, 800.dp))
    }

    @Test
    fun theListKeepsAPhoneishWidth() {
        assertEquals(340.dp, listPaneWidth(707.dp))
        assertEquals(420.dp, listPaneWidth(1280.dp))
        assertEquals(386.86.dp.value, listPaneWidth(841.dp).value, 0.01f)
    }

    @Test
    fun theListPaneHoldsTheLastListAndTheTopOpensBesideIt() {
        assertEquals(0 to null, panesOf(listOf(Screen.Home)))
        assertEquals(0 to 1, panesOf(listOf(Screen.Home, pull(1))))
        assertEquals(0 to 1, panesOf(listOf(Screen.Home, pull(1), Screen.Settings)))
        assertEquals(1 to 2, panesOf(listOf(Screen.Home, Screen.Repo("o", "r"), pull(2))))
        // A repository opened from a pull request takes the list pane, and nothing is beside it until a row opens.
        assertEquals(2 to null, panesOf(listOf(Screen.Home, pull(1), Screen.Repo("o", "r"))))
    }

    @Test
    fun aPullOpenedFromTheListReplacesTheOneBesideIt() {
        val a = pull(1)
        val b = pull(2)
        assertEquals(listOf(1), replacedBeside(listOf(Screen.Home, a, b), b))
        val repo = Screen.Repo("o", "r")
        assertEquals(listOf(1), replacedBeside(listOf(Screen.Home, a, repo), repo))
        // Nothing to replace on a list alone, and Settings and the UI Book open over what is there.
        assertEquals(emptyList(), replacedBeside(listOf(Screen.Home, a), a))
        assertEquals(emptyList(), replacedBeside(listOf(Screen.Home, a, Screen.Settings), Screen.Settings))
        assertEquals(emptyList(), replacedBeside(listOf(Screen.Home, a, Screen.UiBook), Screen.UiBook))
        // Only the top counts as just added.
        assertEquals(emptyList(), replacedBeside(listOf(Screen.Home, a, b), a))
    }
}
