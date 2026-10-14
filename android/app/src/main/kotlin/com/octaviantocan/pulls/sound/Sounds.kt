package com.octaviantocan.pulls.sound

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.octaviantocan.pulls.data.AppSettings
import java.util.concurrent.Executors

/**
 * The app's sounds, as octa-ui plays them: Cuelume's fourteen cues, synthesised live. Call [install]
 * once from the application, then [play] from anywhere on any thread. Nothing here can fail a tap:
 * before the engine is built, with Settings turned off, in silent or vibrate mode, or on a phone
 * with no audio, [play] does nothing.
 *
 * Which cue for which job follows octa-ui's table: a button is a `tap` (subtle when it is secondary),
 * a tab or option a `select`, a switch or checkbox a `toggle`, a confirmed outcome a `success`, a
 * refusal an `error`. octa-ui rejected the whooshes (`navigate`, `open`, `close`), so Pulls never plays
 * them: a page change, a sheet or a menu opening is already the tap that caused it.
 */
object Sounds {
    private const val TAG = "Sounds"

    /**
     * The phone's output level against a laptop's: Cuelume tunes its output for desktop speakers, and a
     * phone's are small. The output stage's soft clip still catches any peak.
     */
    const val PHONE_GAIN = 2.0

    @Volatile
    private var engine: Cuelume? = null
    private var settings: AppSettings? = null

    /** The phone's ringer is on silent or vibrate, so nothing plays. Kept current by a broadcast. */
    @Volatile
    private var silent = false

    fun install(context: Context, settings: AppSettings) {
        if (this.settings != null) return
        this.settings = settings
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        silent = audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        if (audio != null) {
            val ringer = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
                }
            }
            ContextCompat.registerReceiver(app, ringer, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // Parsing the recipes, filling the noise and the room take a moment: not on the main thread.
        val worker = Executors.newSingleThreadExecutor { Thread(it, "cuelume-render").apply { isDaemon = true } }
        worker.execute {
            try {
                val palette = app.assets.open("cuelume/recipes.json").use { Palette.parse(it) }
                val mixer = Mixer(OUTPUT_GAIN * PHONE_GAIN)
                val player = CuePlayer(mixer)
                val built = Cuelume(palette, mixer, Synth(), worker, onVoice = player::wake)
                built.warm()
                engine = built
            } catch (error: Exception) {
                Log.w(TAG, "Sounds are off: ${error.message}")
            }
        }
    }

    /** The engine when a cue may sound, set to the material Settings chose. */
    private fun ready(): Cuelume? {
        val current = settings ?: return null
        val sounds = engine ?: return null
        if (!current.sounds || silent) return null
        sounds.material = Material.of(current.soundMaterial)
        return sounds
    }

    /** Plays [cue] now. Safe anywhere; it plays nothing when sound is off, the phone is silent, or audio is unavailable. */
    fun play(
        cue: Cue,
        emphasis: Emphasis = Emphasis.Normal,
        direction: Direction? = null,
        input: Input? = null,
        key: Key? = null,
        durationMs: Int? = null,
        material: Material? = null,
    ) {
        ready()?.play(cue, PlayOptions(emphasis = emphasis, direction = direction, input = input, key = key, durationMs = durationMs, material = material))
    }

    /** A tap of a finger on a control. */
    fun tap(emphasis: Emphasis = Emphasis.Normal) = play(Cue.Tap, emphasis, input = Input.Touch)

    /** Plays [cue] in [material], for the sound board: it hears every material whichever one is chosen. */
    fun sample(cue: Cue, material: Material, emphasis: Emphasis = Emphasis.Normal, direction: Direction? = null) =
        play(cue, emphasis, direction, input = Input.Touch, material = material)
}
