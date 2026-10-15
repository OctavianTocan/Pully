package com.octaviantocan.pulls.sound

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.octaviantocan.pulls.data.AppSettings

/**
 * The app's vibrations (H18): every cue a control plays is also felt, and gestures that cross a line (a swipe far
 * enough to act, a hold, a page that changes under the finger) answer with a tick. They are the phone's own short
 * effects, so they feel like the rest of Android, and they follow the phone's touch feedback setting as well as the
 * Vibration switch in Settings. Call [install] once from the application, then [cue] or the gesture calls from
 * anywhere. Nothing here can fail a tap: without a vibrator, or with vibration off, they do nothing.
 */
object Haptics {
    private var vibrator: Vibrator? = null
    private var settings: AppSettings? = null

    fun install(context: Context, settings: AppSettings) {
        if (this.settings != null) return
        this.settings = settings
        val app = context.applicationContext
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }?.takeIf { it.hasVibrator() }
    }

    /**
     * What a cue feels like: a light tick for a choice or a quiet button, a click for a main button or a switch, two for a
     * success, a heavy one for a failure.
     */
    fun cue(cue: Cue, emphasis: Emphasis = Emphasis.Normal) {
        when (cue) {
            Cue.Tap -> feel(if (emphasis == Emphasis.Subtle) Feel.Tick else Feel.Click)
            Cue.Toggle -> feel(Feel.Click)
            Cue.Select -> feel(Feel.Tick)
            Cue.Success, Cue.Ready -> feel(Feel.Double)
            Cue.Error, Cue.Warning -> feel(Feel.Heavy)
            else -> {}
        }
    }

    /** A swipe or a drag reached the point where letting go acts, or went back under it. */
    fun threshold() = feel(Feel.Tick)

    /** A hold that opens something, such as a row's actions. */
    fun hold() = feel(Feel.Heavy)

    /** A page changed under the finger, such as the Inbox to Tours. */
    fun page() = feel(Feel.Tick)

    /** A sheet came up and reached its place, or a finger moved it to its other height (J12). */
    fun sheet() = feel(Feel.Click)

    private enum class Feel(val effect: Int) {
        Tick(VibrationEffect.EFFECT_TICK),
        Click(VibrationEffect.EFFECT_CLICK),
        Double(VibrationEffect.EFFECT_DOUBLE_CLICK),
        Heavy(VibrationEffect.EFFECT_HEAVY_CLICK),
    }

    private fun feel(feel: Feel) {
        if (settings?.haptics != true) return
        val buzz = vibrator ?: return
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(feel.effect)
            } else {
                VibrationEffect.createOneShot(if (feel == Feel.Tick) 8L else 14L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            // Touch feedback, so the phone's own "Vibration & haptics > Touch feedback" setting applies (Android 13 and up).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                buzz.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                buzz.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }
}
