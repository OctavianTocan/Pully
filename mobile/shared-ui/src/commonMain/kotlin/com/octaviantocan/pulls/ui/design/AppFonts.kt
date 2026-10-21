package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/**
 * The faces the app's own words can be set in, beside the code's [CodeFont] (a Fonts section with "a font for the app as
 * well as for code": recording 16 Oct 12:10, 06:29 to 06:42; N10). [id] is what the setting stores; an id nobody knows
 * falls back to Geist.
 */
enum class AppFont(val id: String, val label: String) {
    Geist("geist", "Geist"),
    MonaSans("mona-sans", "Mona Sans"),
    System("system", "System sans"),
    Serif("serif", "System serif"),
    ;

    companion object {
        fun of(id: String?): AppFont = entries.firstOrNull { it.id == id } ?: Geist
    }
}

/**
 * The app face in use. Every role of [PullsType] but the numbers' and the code's reads it, so the interface changes
 * together when [selected] does. Numbers stay in Mona Sans and code in the [CodeFonts] face, so columns of digits and
 * code keep their own look whatever the words are set in.
 */
object AppFonts {
    /** The [AppFont.id] of the chosen face; the app writes it from the setting. */
    var selected by mutableStateOf(AppFont.Geist.id)

    val family: FontFamily get() = familyOf(AppFont.of(selected))

    fun familyOf(font: AppFont): FontFamily = when (font) {
        AppFont.Geist -> PullsFonts.sans
        AppFont.MonaSans -> PullsFonts.display
        AppFont.System -> FontFamily.SansSerif
        AppFont.Serif -> FontFamily.Serif
    }
}
