package com.eartap.recorder

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File names and folders.
 *
 *   Recordings/EarTap/2026-10/2026-10-27/Interaction1_2026-10-27_20-35_Office.m4a
 *   └ root ─────────┘ └ month┘ └ day ───┘ └ number ┘ └ date ──┘ └time┘ └place┘
 *
 * The interaction number starts at 1 every day.
 */
object Naming {
    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.US)

    /** "Recordings/EarTap" (Android 12+) or "Music/EarTap" (Android 11 doesn't allow Recordings). */
    val root: String
        get() = (if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS
                 else Environment.DIRECTORY_MUSIC) + "/EarTap"

    fun dayKey(d: Date): String = fmt("yyyy-MM-dd").format(d)

    fun folderFor(d: Date): String = "$root/${fmt("yyyy-MM").format(d)}/${dayKey(d)}/"

    /** "Room 4B / Floor 2" -> "Room-4B-Floor-2". Letters of any language are kept. */
    fun cleanPlace(raw: String): String =
        raw.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(30).ifEmpty { "NoPlace" }

    fun fileName(n: Int, d: Date, place: String): String =
        "Interaction${n}_${dayKey(d)}_${fmt("HH-mm").format(d)}_${cleanPlace(place)}.m4a"

    data class Parsed(val number: Int, val day: String, val time: String, val place: String)

    private val NAME = Regex("""^Interaction(\d+)_(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})_(.+)\.m4a$""")

    fun parse(name: String): Parsed? {
        val m = NAME.matchEntire(name) ?: return null
        val g = m.groupValues
        val n = g[1].toIntOrNull() ?: return null
        return Parsed(n, g[2], "${g[3]}:${g[4]}", g[5].replace('-', ' '))
    }

    /** Next interaction number for that day: highest existing file (or saved counter) + 1. */
    fun nextNumber(c: Context, d: Date): Int {
        val day = dayKey(d)
        var max = Prefs.lastNumber(c, day)
        try {
            c.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("Interaction%$day%"),
                null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val p = parse(cur.getString(0) ?: continue) ?: continue
                    if (p.day == day && p.number > max) max = p.number
                }
            }
        } catch (_: Exception) {
        }
        return max + 1
    }

    fun previewName(c: Context, d: Date = Date()): String = fileName(nextNumber(c, d), d, Prefs.place(c))
}
