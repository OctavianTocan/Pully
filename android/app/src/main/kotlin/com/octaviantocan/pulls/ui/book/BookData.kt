package com.octaviantocan.pulls.ui.book

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

// What the UI Book reads: `book.json` is written by hand, the other three are written from
// android/DESIGN.md and the Kotlin by `pnpm run android:design:update` and are never edited.

internal class BookPage(val id: String, val title: String, val group: String, val component: String?)

internal class BookVariantInfo(val id: String, val title: String, val isDefault: Boolean)

internal class BookComponent(
    val id: String,
    val composable: String,
    val summary: String,
    val usage: List<String>,
    val rules: List<Int>,
    val variants: List<BookVariantInfo>,
)

internal class Prop(val name: String, val type: String, val default: String?)

/** One declaration of a composable, as `sources.json` records it: where it is, its props and its code. */
internal class Declaration(val file: String, val line: Int, val doc: String, val receiver: String?, val props: List<Prop>, val source: String)

internal class ColorToken(val name: String, val light: String, val dark: String)

internal class TypeToken(val name: String, val family: String, val size: Float, val weight: Int, val lineHeight: Float, val letterSpacing: Float)

internal class Scale(val name: String, val value: Float)

/** The size a DESIGN.md component token asks for, in dp. Null means the token does not fix that side. */
internal class ComponentToken(val name: String, val width: Float?, val height: Float?)

internal class RuleInfo(val n: Int, val title: String, val source: String, val checkedBy: List<String>, val text: String)

internal class BookData(
    val pages: List<BookPage>,
    val components: Map<String, BookComponent>,
    val declarations: Map<String, List<Declaration>>,
    val colors: List<ColorToken>,
    val typography: List<TypeToken>,
    val rounded: List<Scale>,
    val spacing: List<Scale>,
    val elevation: List<Scale>,
    val motion: List<Pair<String, String>>,
    val tokens: Map<String, ComponentToken>,
    val rules: List<RuleInfo>,
) {
    companion object {
        fun load(context: Context): BookData = parse { name -> context.assets.open("book/$name").bufferedReader().use { it.readText() } }

        /** Reads the four files of the book through [text], which returns a file's contents by its name. */
        fun parse(text: (String) -> String): BookData {
            val book = JSONObject(text("book.json"))
            val spec = JSONObject(text("spec.json"))
            val sources = JSONObject(text("sources.json"))
            val rules = JSONArray(text("rules.json"))
            return BookData(
                pages = book.getJSONArray("pages").objects().map {
                    BookPage(it.getString("id"), it.getString("title"), it.getString("group"), it.optString("component").ifEmpty { null })
                },
                components = book.getJSONObject("components").entries().associate { (id, c) ->
                    id to BookComponent(
                        id = id,
                        composable = c.getString("composable"),
                        summary = c.getString("summary"),
                        usage = c.getJSONArray("usage").strings(),
                        rules = c.optJSONArray("rules")?.ints().orEmpty(),
                        variants = c.getJSONArray("variants").objects().map { BookVariantInfo(it.getString("id"), it.getString("title"), it.optBoolean("default")) },
                    )
                },
                declarations = sources.entries().associate { (id, entry) ->
                    id to entry.getJSONArray("declarations").objects().map { d ->
                        Declaration(
                            file = d.getString("file"),
                            line = d.getInt("line"),
                            doc = d.optString("doc"),
                            receiver = d.optString("receiver").ifEmpty { null },
                            props = d.getJSONArray("params").objects().map { Prop(it.getString("name"), it.getString("type"), it.optString("default").ifEmpty { null }) },
                            source = d.getString("source"),
                        )
                    }
                },
                colors = spec.getJSONObject("colors").entries().map { (name, c) -> ColorToken(name, c.getString("light"), c.getString("dark")) },
                typography = spec.getJSONObject("typography").entries().map { (name, t) ->
                    TypeToken(name, t.getString("fontFamily"), t.getDouble("fontSize").toFloat(), t.getInt("fontWeight"), t.getDouble("lineHeight").toFloat(), t.optDouble("letterSpacing", 0.0).toFloat())
                },
                rounded = spec.getJSONObject("rounded").scale(),
                spacing = spec.getJSONObject("spacing").scale(),
                elevation = spec.getJSONObject("elevation").scale(),
                motion = spec.getJSONObject("motion").let { m -> m.keys().asSequence().map { it to m.getString(it) }.toList() },
                tokens = spec.getJSONObject("components").entries().associate { (name, c) ->
                    name to ComponentToken(name, c.floatOrNull("width"), c.floatOrNull("height"))
                },
                rules = rules.objects().map { r ->
                    RuleInfo(r.getInt("n"), r.getString("title"), r.getString("source"), r.getJSONArray("checkedBy").strings(), r.getString("text"))
                },
            )
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

private fun JSONArray.ints(): List<Int> = List(length()) { getInt(it) }

private fun JSONObject.entries(): List<Pair<String, JSONObject>> = keys().asSequence().map { it to getJSONObject(it) }.toList()

private fun JSONObject.scale(): List<Scale> = keys().asSequence().map { Scale(it, getDouble(it).toFloat()) }.toList()

private fun JSONObject.floatOrNull(key: String): Float? = if (isNull(key)) null else getDouble(key).toFloat()

/** `#RRGGBB` or `#RRGGBBAA`, the way DESIGN.md writes a colour. */
internal fun hexColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.take(6).toLong(16)
    val alpha = if (digits.length == 8) digits.substring(6).toLong(16) else 255L
    return Color(((alpha shl 24) or rgb).toInt())
}
