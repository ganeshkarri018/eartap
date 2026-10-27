package com.eartap.recorder

import android.content.Context
import android.view.KeyEvent

/** Small wrapper around SharedPreferences for the user's settings. */
object Prefs {
    private const val FILE = "eartap"
    private const val KEY_TRIGGER = "trigger_key"
    private const val KEY_EARBUD_MIC = "earbud_mic"

    /** Earbud command that toggles recording. Triple tap usually sends "Previous track". */
    val TRIGGER_OPTIONS = listOf(
        "Previous track (triple tap)" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "Next track" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "Play / Pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    )

    fun triggerKey(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_TRIGGER, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun setTriggerKey(c: Context, key: Int) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_TRIGGER, key).apply()

    fun useEarbudMic(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_EARBUD_MIC, false)

    fun setUseEarbudMic(c: Context, on: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_EARBUD_MIC, on).apply()
}
