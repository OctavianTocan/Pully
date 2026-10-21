package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** The faces code can be shown in. [id] is what the setting stores; an id nobody knows falls back to Geist Mono. */
enum class CodeFont(val id: String, val label: String) {
    GeistMono("geist-mono", "Geist Mono"),
    JetBrainsMono("jetbrains-mono", "JetBrains Mono"),
    FiraCode("fira-code", "Fira Code"),
    SourceCodePro("source-code-pro", "Source Code Pro"),
    IbmPlexMono("ibm-plex-mono", "IBM Plex Mono"),
    RobotoMono("roboto-mono", "Roboto Mono"),
    System("system", "System monospace"),
    ;

    companion object {
        fun of(id: String?): CodeFont = entries.firstOrNull { it.id == id } ?: GeistMono
    }
}

/**
 * The code face in use. [PullsType.mono] reads it, so the diff, the tour's sketches, the brief's code and every
 * identifier set in the code face change together when [selected] does. Only the regular weight of the added faces
 * is bundled; a medium run in one of them is drawn regular.
 */
object CodeFonts {
    /** The [CodeFont.id] of the chosen face; the app writes it from the setting. */
    var selected by mutableStateOf(CodeFont.GeistMono.id)

    val family: FontFamily get() = familyOf(CodeFont.of(selected))

    fun familyOf(font: CodeFont): FontFamily = when (font) {
        CodeFont.GeistMono -> PullsFonts.mono
        CodeFont.JetBrainsMono -> jetBrains
        CodeFont.FiraCode -> firaCode
        CodeFont.SourceCodePro -> sourceCodePro
        CodeFont.IbmPlexMono -> ibmPlexMono
        CodeFont.RobotoMono -> robotoMono
        CodeFont.System -> FontFamily.Monospace
    }

    private val jetBrains = FontFamily(bundledFont("jetbrains_mono_regular", FontWeight.Normal))
    private val firaCode = FontFamily(bundledFont("fira_code_regular", FontWeight.Normal))
    private val sourceCodePro = FontFamily(bundledFont("source_code_pro_regular", FontWeight.Normal))
    private val ibmPlexMono = FontFamily(bundledFont("ibm_plex_mono_regular", FontWeight.Normal))
    private val robotoMono = FontFamily(bundledFont("roboto_mono_regular", FontWeight.Normal))
}
