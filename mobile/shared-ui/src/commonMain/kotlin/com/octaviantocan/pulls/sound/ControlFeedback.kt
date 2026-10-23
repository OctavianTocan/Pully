package com.octaviantocan.pulls.sound

/** Existing touch-only roles; none of these plays an audible control cue. */
enum class NativeGestureFeedback { Sheet, Threshold, Page }

/** Native audio/haptics preserve the existing cue vocabulary and each host's user/system settings. */
interface NativeControlFeedback {
    fun play(cue: Cue, emphasis: Emphasis, direction: Direction?, input: Input?)
    fun hold()
    fun gesture(role: NativeGestureFeedback)
}

/** The native host installs feedback before presenting interactive controls; there is no silent substitute. */
object ControlFeedback {
    private var native: NativeControlFeedback? = null

    fun install(feedback: NativeControlFeedback) { native = feedback }

    internal fun installed(): NativeControlFeedback =
        requireNotNull(native) { "Install native control feedback before showing interactive controls." }

    fun play(cue: Cue, emphasis: Emphasis = Emphasis.Normal, direction: Direction? = null, input: Input? = null) =
        requireNotNull(native) { "Install native control feedback before showing interactive controls." }.play(cue, emphasis, direction, input)

    fun hold() = requireNotNull(native) { "Install native control feedback before showing interactive controls." }.hold()

    fun gesture(role: NativeGestureFeedback) =
        requireNotNull(native) { "Install native control feedback before showing interactive controls." }.gesture(role)
}
