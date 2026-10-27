package com.eartap.recorder

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Volume-down x3 shortcut.
 *
 * Every press still lowers the volume like normal. On the 3rd quick press the
 * volume is put back to where it was and the caller toggles recording.
 * Holding the key down (auto-repeat) never counts, so you can still lower the
 * volume quickly without starting a recording by accident.
 */
object VolumeTrigger {
    private const val WINDOW_MS = 1500L      // all 3 presses within 1.5 s
    private const val MIN_GAP_MS = 120L      // faster than this = key auto-repeat

    private val presses = ArrayDeque<Long>()
    private var lastRaw = 0L
    private var volumeBefore = -1

    /** Returns true when this press completes a triple press. */
    @Synchronized
    fun onVolumeDown(am: AudioManager, showUi: Boolean, isRepeat: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        val tooFast = now - lastRaw < MIN_GAP_MS
        lastRaw = now
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0

        if (isRepeat || tooFast) {
            lower(am, flags)
            return false
        }

        while (presses.isNotEmpty() && now - presses.first() > WINDOW_MS) presses.removeFirst()
        if (presses.isEmpty()) volumeBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        presses.addLast(now)

        if (presses.size >= 3) {
            presses.clear()
            try {
                if (volumeBefore >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0)
            } catch (_: Exception) {
            }
            return true
        }
        lower(am, flags)
        return false
    }

    private fun lower(am: AudioManager, flags: Int) {
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
        } catch (_: Exception) {
        }
    }
}

/** Friendly name for the "last shortcut received" line. */
fun describeKey(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Earbud: previous track"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "Earbud: next track"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "Earbud: play / pause"
    else -> "Earbud: " + KeyEvent.keyCodeToString(keyCode)
}
