package com.eartap.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var btnService: Button
    private lateinit var btnRecord: Button
    private lateinit var listView: ListView

    private val recordingUris = mutableListOf<Uri>()
    private val recordingLabels = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private var player: MediaPlayer? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshUi()
            loadRecordings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnService = findViewById(R.id.btnService)
        btnRecord = findViewById(R.id.btnRecord)
        listView = findViewById(R.id.listRecordings)

        // Which earbud command triggers recording
        val spinner = findViewById<Spinner>(R.id.spinnerTrigger)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Prefs.TRIGGER_OPTIONS.map { it.first }
        )
        val current = Prefs.TRIGGER_OPTIONS.indexOfFirst { it.second == Prefs.triggerKey(this) }
        spinner.setSelection(if (current >= 0) current else 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setTriggerKey(this@MainActivity, Prefs.TRIGGER_OPTIONS[pos].second)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val sw = findViewById<Switch>(R.id.switchEarbudMic)
        sw.isChecked = Prefs.useEarbudMic(this)
        sw.setOnCheckedChangeListener { _, on -> Prefs.setUseEarbudMic(this, on) }

        btnService.setOnClickListener {
            if (EarTapService.isRunning) {
                startService(Intent(this, EarTapService::class.java).setAction(EarTapService.ACTION_STOP))
            } else {
                askPermissionsThenStart()
            }
        }

        btnRecord.setOnClickListener {
            if (!EarTapService.isRunning) {
                Toast.makeText(this, "Tap 'Start listening' first", Toast.LENGTH_SHORT).show()
            } else {
                startService(Intent(this, EarTapService::class.java).setAction(EarTapService.ACTION_TOGGLE))
            }
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, recordingLabels)
        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, pos, _ -> togglePlayback(recordingUris[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showRecordingMenu(recordingUris[pos], recordingLabels[pos])
            true
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(EarTapService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        refreshUi()
        loadRecordings()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        stopPlayback()
        super.onStop()
    }

    // ---------------------------------------------------------------- start / permissions

    private fun askPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startEarTap() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startEarTap()
        } else {
            Toast.makeText(this, "Microphone permission is needed to record", Toast.LENGTH_LONG).show()
        }
    }

    private fun startEarTap() {
        startForegroundService(Intent(this, EarTapService::class.java).setAction(EarTapService.ACTION_START))
    }

    private fun refreshUi() {
        val state = when {
            EarTapService.isRecording -> "● Recording"
            EarTapService.isRunning -> "Listening for earbud taps"
            else -> "Off"
        }
        status.text = state + "\nLast earbud button: " + EarTapService.lastKey
        btnService.text = if (EarTapService.isRunning) "Stop listening" else "Start listening"
        btnRecord.text = if (EarTapService.isRecording) "Stop recording" else "Record now (test)"
    }

    // ---------------------------------------------------------------- recordings list

    private fun loadRecordings() {
        recordingUris.clear()
        recordingLabels.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION
        )
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Music/EarTap%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                    )
                    val secs = c.getLong(durCol) / 1000
                    val dur = if (secs > 0) "  (%d:%02d)".format(secs / 60, secs % 60) else ""
                    recordingUris += uri
                    recordingLabels += c.getString(nameCol) + dur
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not list recordings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun togglePlayback(uri: Uri) {
        if (player != null) {
            stopPlayback()
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            Toast.makeText(this, "Playing — tap again to stop", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            stopPlayback()
            Toast.makeText(this, "Can't play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
    }

    private fun showRecordingMenu(uri: Uri, label: String) {
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("Share", "Delete")) { _, which ->
                if (which == 0) {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("audio/mp4")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Share recording"))
                } else {
                    stopPlayback()
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Delete it from your Files app instead", Toast.LENGTH_LONG).show()
                    }
                    loadRecordings()
                }
            }
            .show()
    }
}
