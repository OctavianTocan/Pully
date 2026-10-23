package com.octaviantocan.pulls.sound

/*
 * Pully's sounds are Cuelume's (https://cuelume.dev, MIT, Daniel Belyi), ported to Kotlin from
 * version 0.2.8 and synthesised live, with no audio files: fourteen cues, one per interface job,
 * in four materials. The Default tap is Pully's own: a dry click where Cuelume rings glass. This is the vocabulary a caller speaks. Pick a cue by its job, never by how
 * it sounds, exactly as octa-ui does.
 */

/** The fourteen interface jobs. The id is the name the recipes file and the web package use. */
enum class Cue {
    /** Buttons, links, direct activation. */
    Tap,

    /** Text entry. Pully does not play it: the phone's keyboard already clicks. */
    Type,

    /** Dropdowns, menus, tabs, lists. */
    Select,

    /** Switching between two states: switch, checkbox, star. */
    Toggle,

    /** Air drawing up. octa-ui rejected the air in `open`, so Pully never plays it outside the sound board. */
    Open,

    /** Air falling shut. Rejected like [Open]. */
    Close,

    /** Confirmed completion. */
    Success,

    /** A recoverable failure or refusal. */
    Error,

    /** A whoosh. Rejected like [Open]. */
    Navigate,

    /** Done, but it needs a look. */
    Warning,

    /** Slow work started. */
    Loading,

    /** A result is there, nothing was confirmed. */
    Ready,

    /** Blocked until the user answers. */
    Attention,

    /** A number animating to a new value. */
    Count;

    val id: String get() = name.lowercase()

    /** The three whooshes: shown on the sound board, never played by the app's own controls. */
    val isAir: Boolean get() = this == Open || this == Close || this == Navigate
}

/** The four finished materials; every one carries all fourteen cues. */
enum class Material(val label: String) {
    /** Warm: clicks and wood for controls, soft mallets and glass for outcomes. */
    Default("Default"),

    /** Dry and precise: a shutter click, a latch, struck metal. */
    Mech("Mech"),

    /** Playful: a knock, a drip, a cork, a kalimba. */
    Bubble("Bubble"),

    /** A premium trackpad: clicks over a deep note that rings. */
    Press("Press");

    val id: String get() = name.lowercase()

    companion object {
        fun of(id: String?): Material = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** How much the action matters. Each level is its own arrangement of the cue, not a volume knob. */
enum class Emphasis { Subtle, Normal, Strong }

/** Which way something moved: a later tab or an on switch is forward. */
enum class Direction(val sign: Int) { Forward(1), Back(-1) }

/** What activated a tap, so the tap can answer to it: touch is softer and rounder than a mouse. */
enum class Input { Mouse, Touch, Pen, Keyboard }

/** Which key a [Cue.Type] stroke is. */
enum class Key { Printable, Space, Delete, Enter }

/** What one play may bend. Each field is ignored by the cues it does not belong to. */
data class PlayOptions(
    /** 0 to 1, for this play only. */
    val volume: Float = 1f,
    val emphasis: Emphasis = Emphasis.Normal,
    val direction: Direction? = null,
    val key: Key? = null,
    val input: Input? = null,
    /** How long a [Cue.Count] runs, in milliseconds, clamped to 300..2000. */
    val durationMs: Int? = null,
    /** The material for this play only; the chosen one stays. */
    val material: Material? = null,
)
