package com.octaviantocan.pulls.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Feeds the [Mixer] to the speaker. One thread owns one low-latency `AudioTrack`, and it only runs
 * while something rings: a track with nothing to play is paused after two seconds and released after
 * a minute, and the next cue starts it again.
 *
 * The track is `USAGE_ASSISTANCE_SONIFICATION`, the usage Android gives its own tap sounds. It follows
 * the phone's touch-sound volume, is silent in silent and vibrate mode, and takes no audio focus, so
 * music keeps playing under it.
 */
class CuePlayer(private val mixer: Mixer) {
    private val signal = Semaphore(0)
    private var thread: Thread? = null

    @Volatile
    private var broken = false

    /** Something was added to the mixer: start the thread if it is not running, and wake it. */
    @Synchronized
    fun wake() {
        if (broken) return
        if (thread == null) {
            thread = Thread(::run, "cuelume-mixer").apply {
                isDaemon = true
                start()
            }
        }
        signal.release()
    }

    private fun createTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val smallest = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(smallest, CHUNK_FRAMES * BUFFER_CHUNKS * BYTES_PER_FRAME))
            .build()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = FloatArray(CHUNK_FRAMES * 2)
        var track: AudioTrack? = null
        var playing = false
        var idleSince = SystemClock.elapsedRealtime()
        try {
            while (true) {
                if (mixer.busy) {
                    val open = track ?: createTrack().also { track = it }
                    if (!playing) {
                        open.play()
                        playing = true
                    }
                    mixer.render(chunk, CHUNK_FRAMES)
                    if (open.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) < 0) {
                        // the audio server went away: start over with a new track
                        open.release()
                        track = null
                        playing = false
                    }
                    idleSince = SystemClock.elapsedRealtime()
                    continue
                }
                val idle = SystemClock.elapsedRealtime() - idleSince
                val open = track
                if (open != null && playing && idle >= PAUSE_AFTER_MS) {
                    open.pause()
                    open.flush()
                    playing = false
                } else if (open != null && !playing && idle >= RELEASE_AFTER_MS) {
                    open.release()
                    track = null
                }
                // sleep until a cue arrives or the next thing to tidy is due
                val wait = when {
                    open == null -> Long.MAX_VALUE
                    playing -> PAUSE_AFTER_MS - idle
                    else -> RELEASE_AFTER_MS - idle
                }
                signal.tryAcquire(wait.coerceIn(1L, MAX_WAIT_MS), TimeUnit.MILLISECONDS)
                signal.drainPermits()
            }
        } catch (error: Throwable) {
            // no audio on this device, or the track could not start: stay quiet rather than crash a tap
            Log.w(TAG, "Cue sounds stopped: ${error.message}")
            broken = true
        } finally {
            track?.release()
        }
    }

    private companion object {
        const val TAG = "CuePlayer"

        /** 10 ms of audio per write. */
        const val CHUNK_FRAMES = 480
        const val BUFFER_CHUNKS = 3
        const val BYTES_PER_FRAME = 8
        const val PAUSE_AFTER_MS = 2_000L
        const val RELEASE_AFTER_MS = 60_000L
        const val MAX_WAIT_MS = 3_600_000L
    }
}
