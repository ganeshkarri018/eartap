package com.eartap.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the timeline PDF and saves it to Documents/EarTap/Reports. */
object Report {
    private const val PAGE_W = 595   // A4 in points
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    const val FOLDER = "Documents/EarTap/Reports"

    fun save(c: Context, entries: List<Entry>, incidentsOnly: Boolean): Uri {
        val now = Date()
        val name = "EarTap_Timeline_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(now) + ".pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/EarTap/Reports/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = c.contentResolver
        val uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Could not create the report file")
        try {
            val out = cr.openOutputStream(uri) ?: throw IllegalStateException("Could not open the report file")
            out.use { write(entries, incidentsOnly, now, it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }

    // ------------------------------------------------------------ drawing

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false, color: Int = Color.BLACK) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.DEFAULT
            }
        }

    private class Writer(val doc: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var pageNo = 0
        private var y = 0f
        private val bottom = PAGE_H - MARGIN - 16f
        private val width = PAGE_W - 2 * MARGIN
        private val footer = paint(8f, color = Color.GRAY)
        private val rulePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.7f }

        private fun newPage() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
        }

        fun finish() {
            val p = page ?: return
            p.canvas.drawText("EarTap timeline · page $pageNo", MARGIN, PAGE_H - MARGIN / 2, footer)
            doc.finishPage(p)
            page = null
        }

        /** Wrapped text that flows onto new pages line by line. */
        fun text(s: String, p: TextPaint, indent: Float = 0f, after: Float = 4f) {
            if (page == null) newPage()
            val w = (width - indent).toInt()
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, w).build()
            for (i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i).toFloat()
                val bot = layout.getLineBottom(i).toFloat()
                if (y + (bot - top) > bottom) newPage()
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate(MARGIN + indent, y - top)
                canvas.clipRect(0f, top, w.toFloat(), bot)
                layout.draw(canvas)
                canvas.restore()
                y += bot - top
            }
            y += after
        }

        fun rule() {
            if (page == null || y + 12f > bottom) newPage()
            page!!.canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rulePaint)
            y += 12f
        }

        fun keepTogether(h: Float) {
            if (page == null || y + h > bottom) newPage()
        }
    }

    private fun write(entries: List<Entry>, incidentsOnly: Boolean, now: Date, out: OutputStream) {
        val full = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val title = paint(18f, bold = true)
        val head = paint(11.5f, bold = true)
        val label = paint(9.5f, bold = true, color = Color.DKGRAY)
        val body = paint(10f)
        val small = paint(8.5f, color = Color.DKGRAY)
        val mono = paint(8f, mono = true, color = Color.DKGRAY)

        val doc = PdfDocument()
        val w = Writer(doc)

        w.text(if (incidentsOnly) "Workplace incident timeline" else "Workplace timeline", title, after = 6f)
        w.text("Created ${full.format(now)} on ${Integrity.deviceInfo()} with EarTap", small, after = 2f)
        w.text(
            (if (incidentsOnly) "Entries tagged Incident" else "All entries") + ": ${entries.size}, oldest first",
            small, after = 6f
        )
        w.text(
            "Times come from the phone's clock. For each audio recording, a SHA-256 fingerprint was " +
                "calculated when the file was saved. Calculating it again on the original file and getting " +
                "the same value shows the file has not been changed since.",
            small, after = 10f
        )
        w.rule()

        entries.forEachIndexed { i, e ->
            w.keepTogether(90f)
            val start = full.format(Date(e.time))
            val range = if (e.endTime > e.time) "$start – ${clock.format(Date(e.endTime))}" else start
            val tag = when {
                e.isIncident -> "INCIDENT"
                e.isMeeting -> "MEETING"
                else -> "UNTAGGED"
            }
            w.text("${i + 1}.  $range   ·   $tag", head, after = 1f)
            w.text(if (e.isRecording) "Audio recording" else "Written note (no audio)", small, after = 6f)

            fun field(name: String, value: String) =
                w.text("$name: ${value.ifBlank { "—" }}", body, after = 2f)

            field("Place", e.place)
            field("Who was involved", e.who)
            field("Witnesses", e.witnesses)
            w.text("What happened:", label, after = 1f)
            w.text(e.what.ifBlank { "No notes written." }, body, indent = 10f, after = 4f)

            if (e.isRecording) {
                val dur = formatDuration(e.durationMs)
                field("Audio file", e.fileName + (if (dur != null) "  ($dur)" else ""))
                field("Folder", e.folder.trimEnd('/'))
                if (e.sha256.isNotBlank()) {
                    val taken = if (e.hashedAt > 0) full.format(Date(e.hashedAt)) else "unknown time"
                    w.text("SHA-256 fingerprint, taken $taken:", label, after = 1f)
                    w.text(e.sha256, mono, indent = 10f, after = 2f)
                    if (e.sizeBytes > 0) field("File size", "${e.sizeBytes} bytes")
                } else {
                    field("Fingerprint", "not taken (recorded with an older EarTap version)")
                }
                if (e.device.isNotBlank()) field("Recorded on", e.device)
            }
            w.text("", small, after = 2f)
            w.rule()
        }

        w.finish()
        doc.writeTo(out)
        doc.close()
    }

    fun formatDuration(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
               else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
