package com.octaviantocan.pulls.sound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/*
 * The recipes: what each cue is made of, in each material. They are Cuelume's own, generated into
 * `assets/cuelume/recipes.json` by `android/scripts/gen-cuelume.mjs` with its `knock`, `struck` and
 * `click` builders already expanded, so a layer here is exactly a layer the web package plays. The
 * few Pully replaces (the Default tap, whose ringing glass Tavi hates) are in `cuelume-pulls.mjs`,
 * built with the same builders, and the file names them under `pulls`.
 */

/** A layer is a sine tone or a burst of filtered noise, swelling in along a line and dying away exponentially. */
@Serializable
enum class Kind {
    @SerialName("tone") Tone,
    @SerialName("noise") Noise,
}

@Serializable
enum class FilterType {
    @SerialName("bandpass") Bandpass,
    @SerialName("lowpass") Lowpass,
}

/** The lowest emphasis that plays a layer. A layer without one plays at every emphasis: `normal` adds ornament and `strong` adds a layer only it plays. */
@Serializable
enum class From {
    @SerialName("subtle") Subtle,
    @SerialName("normal") Normal,
    @SerialName("strong") Strong,
}

/**
 * One layer of a cue. Times are seconds, frequencies Hz. [decay] is the time the layer takes to fall
 * 60 dB, not an exponential time constant. A tone has [frequency]; a noise layer has [filterType]
 * and [filterFrequency]. [glideTo] bends the pitch or the filter exponentially.
 */
@Serializable
data class Layer(
    val kind: Kind,
    val from: From? = null,
    val frequency: Double = 0.0,
    val filterType: FilterType = FilterType.Bandpass,
    val filterFrequency: Double = 0.0,
    val filterQ: Double? = null,
    val attack: Double,
    val decay: Double,
    val peak: Double,
    val glideTo: Double? = null,
    val glideTime: Double? = null,
    /** The layer lasts as long as a [Cue.Count] roll does. */
    val stretch: Boolean = false,
    /** A note that walks up its chord when the cue repeats quickly (the `press` material). */
    val note: Boolean = false,
    /** Seconds after the play starts; set when a release is laid after its press. */
    val offset: Double? = null,
) {
    /** Where the pitch or the filter starts. */
    val start: Double get() = if (kind == Kind.Tone) frequency else filterFrequency
}

/** How much each strike of a cue moves: pitch and level, as fractions. */
@Serializable
data class Vary(val pitch: Double, val level: Double)

@Serializable
data class Recipe(
    val masterGain: Double,
    val layers: List<Layer>,
    /** The let-go of a press; only the themes that have one make it audible. */
    val release: List<Layer>? = null,
    /** Semitones the notes step up on quick repeats. */
    val walk: List<Int>? = null,
    val vary: Vary? = null,
    /** How much of the cue reaches the room, relative to the rest. */
    val room: Double? = null,
)

@Serializable
private data class PaletteFile(val version: String, val themes: Map<String, Map<String, Recipe>>)

/** Every material's recipe for every cue. */
class Palette private constructor(val version: String, private val themes: Map<Material, Map<Cue, Recipe>>) {
    operator fun get(material: Material, cue: Cue): Recipe = themes.getValue(material).getValue(cue)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(stream: InputStream): Palette = parse(stream.readBytes().decodeToString())

        fun parse(text: String): Palette {
            val file = json.decodeFromString<PaletteFile>(text)
            val themes = Material.entries.associateWith { material ->
                val recipes = file.themes.getValue(material.id)
                Cue.entries.associateWith { cue -> recipes.getValue(cue.id) }
            }
            return Palette(file.version, themes)
        }
    }
}
