package com.eartap.recorder

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/** The user's settings, stored on the phone. */
object Prefs {
    private const val FILE = "eartap"
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Earbud command that starts / stops recording (set the tap gesture to this in the earbud settings). */
    val TRIGGER_OPTIONS = listOf(
        "Previous track (triple tap)" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "Next track" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "Play / Pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    )

    fun triggerKey(c: Context): Int = sp(c).getInt("trigger_key", KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun setTriggerKey(c: Context, key: Int) = sp(c).edit().putInt("trigger_key", key).apply()

    fun useEarbudMic(c: Context): Boolean = sp(c).getBoolean("earbud_mic", false)
    fun setUseEarbudMic(c: Context, on: Boolean) = sp(c).edit().putBoolean("earbud_mic", on).apply()

    fun earbudTrigger(c: Context): Boolean = sp(c).getBoolean("earbud_trigger", true)
    fun setEarbudTrigger(c: Context, on: Boolean) = sp(c).edit().putBoolean("earbud_trigger", on).apply()

    fun volumeTrigger(c: Context): Boolean = sp(c).getBoolean("volume_trigger", true)
    fun setVolumeTrigger(c: Context, on: Boolean) = sp(c).edit().putBoolean("volume_trigger", on).apply()

    fun place(c: Context): String = sp(c).getString("place", "Office") ?: "Office"
    fun setPlace(c: Context, place: String) = sp(c).edit().putString("place", place).apply()

    fun autoPlace(c: Context): Boolean = sp(c).getBoolean("auto_place", false)
    fun setAutoPlace(c: Context, on: Boolean) = sp(c).edit().putBoolean("auto_place", on).apply()

    /** Highest interaction number used on a given day (yyyy-MM-dd). */
    fun lastNumber(c: Context, day: String): Int = sp(c).getInt("num_$day", 0)
    fun setLastNumber(c: Context, day: String, n: Int) = sp(c).edit().putInt("num_$day", n).apply()
}
