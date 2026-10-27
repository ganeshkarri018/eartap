package com.eartap.recorder

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * One item on the timeline: either a recording (with its notes and fingerprint)
 * or a written note with no audio.
 */
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    var kind: String = KIND_NOTE,
    var category: String = "",          // INCIDENT, MEETING or "" (untagged)
    var time: Long = System.currentTimeMillis(),
    var endTime: Long = 0L,
    var place: String = "",
    var who: String = "",
    var witnesses: String = "",
    var what: String = "",
    // recordings only
    var fileName: String = "",
    var folder: String = "",
    var durationMs: Long = 0L,
    var sha256: String = "",
    var hashedAt: Long = 0L,
    var sizeBytes: Long = 0L,
    var device: String = "",
) {
    companion object {
        const val KIND_RECORDING = "recording"
        const val KIND_NOTE = "note"
        const val INCIDENT = "incident"
        const val MEETING = "meeting"

        fun fromJson(o: JSONObject) = Entry(
            id = o.optString("id", UUID.randomUUID().toString()),
            kind = o.optString("kind", KIND_NOTE),
            category = o.optString("category", ""),
            time = o.optLong("time", 0L),
            endTime = o.optLong("endTime", 0L),
            place = o.optString("place", ""),
            who = o.optString("who", ""),
            witnesses = o.optString("witnesses", ""),
            what = o.optString("what", ""),
            fileName = o.optString("fileName", ""),
            folder = o.optString("folder", ""),
            durationMs = o.optLong("durationMs", 0L),
            sha256 = o.optString("sha256", ""),
            hashedAt = o.optLong("hashedAt", 0L),
            sizeBytes = o.optLong("sizeBytes", 0L),
            device = o.optString("device", ""),
        )
    }

    val isIncident: Boolean get() = category == INCIDENT
    val isMeeting: Boolean get() = category == MEETING
    val isRecording: Boolean get() = kind == KIND_RECORDING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("category", category)
        .put("time", time).put("endTime", endTime).put("place", place)
        .put("who", who).put("witnesses", witnesses).put("what", what)
        .put("fileName", fileName).put("folder", folder).put("durationMs", durationMs)
        .put("sha256", sha256).put("hashedAt", hashedAt).put("sizeBytes", sizeBytes)
        .put("device", device)
}

/** Notes and fingerprints, stored privately inside the app (survives updates). */
object Journal {
    private const val FILE = "journal.json"

    @Synchronized
    fun all(c: Context): MutableList<Entry> {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i -> Entry.fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun upsert(c: Context, e: Entry) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list += e
        write(c, list)
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        val list = all(c)
        if (list.removeAll { it.id == id }) write(c, list)
    }

    @Synchronized
    fun byFile(c: Context, fileName: String): Entry? = all(c).firstOrNull { it.fileName == fileName }

    private fun write(c: Context, list: List<Entry>) {
        val tmp = File(c.filesDir, "$FILE.tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
        tmp.renameTo(File(c.filesDir, FILE))
    }
}

/** File fingerprints: if the SHA-256 is the same later, the file hasn't been changed. */
object Integrity {
    /** Returns (sha256 hex, size in bytes), or null if the file can't be read. */
    fun sha256(cr: ContentResolver, uri: Uri): Pair<String, Long>? = try {
        cr.openInputStream(uri)?.use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
            md.digest().joinToString("") { "%02x".format(it) } to total
        }
    } catch (_: Exception) {
        null
    }

    fun deviceInfo(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
}
