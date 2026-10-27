package com.eartap.recorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_START = 1
        private const val REQ_LOCATION = 2
        private const val REQ_DELETE = 3
    }

    private data class Rec(val uri: Uri, val name: String, val durationMs: Long, val addedSec: Long)

    private lateinit var statusChip: TextView
    private lateinit var btnRecord: View
    private lateinit var recordIcon: ImageView
    private lateinit var recordPulse: View
    private lateinit var timer: TextView
    private lateinit var recordLabel: TextView
    private lateinit var nextFileLabel: TextView
    private lateinit var nextFile: TextView
    private lateinit var folderPath: TextView
    private lateinit var placeInput: EditText
    private lateinit var switchAutoPlace: Switch
    private lateinit var switchListening: Switch
    private lateinit var switchVolume: Switch
    private lateinit var switchEarbud: Switch
    private lateinit var switchEarbudMic: Switch
    private lateinit var spinnerTrigger: Spinner
    private lateinit var lastTrigger: TextView
    private lateinit var recordingsContainer: LinearLayout
    private lateinit var recordingsCount: TextView
    private lateinit var emptyState: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private val playIcons = mutableMapOf<Uri, ImageView>()
    private var pulse: ObjectAnimator? = null
    private var pendingAction: (() -> Unit)? = null
    private var updatingUi = false
    private var wasRecording = false

    private val ticker = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 500)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshUi()
            if (wasRecording != EarTapService.isRecording) {
                wasRecording = EarTapService.isRecording
                loadRecordings()
            }
        }
    }

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusChip = findViewById(R.id.statusChip)
        btnRecord = findViewById(R.id.btnRecord)
        recordIcon = findViewById(R.id.recordIcon)
        recordPulse = findViewById(R.id.recordPulse)
        timer = findViewById(R.id.timer)
        recordLabel = findViewById(R.id.recordLabel)
        nextFileLabel = findViewById(R.id.nextFileLabel)
        nextFile = findViewById(R.id.nextFile)
        folderPath = findViewById(R.id.folderPath)
        placeInput = findViewById(R.id.placeInput)
        switchAutoPlace = findViewById(R.id.switchAutoPlace)
        switchListening = findViewById(R.id.switchListening)
        switchVolume = findViewById(R.id.switchVolume)
        switchEarbud = findViewById(R.id.switchEarbud)
        switchEarbudMic = findViewById(R.id.switchEarbudMic)
        spinnerTrigger = findViewById(R.id.spinnerTrigger)
        lastTrigger = findViewById(R.id.lastTrigger)
        recordingsContainer = findViewById(R.id.recordingsContainer)
        recordingsCount = findViewById(R.id.recordingsCount)
        emptyState = findViewById(R.id.emptyState)

        btnRecord.setOnClickListener {
            withPermissions { sendToService(EarTapService.ACTION_TOGGLE, foreground = true) }
        }

        // Meeting place
        placeInput.setText(Prefs.place(this))
        placeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.setPlace(this@MainActivity, s?.toString()?.trim().orEmpty())
                updateFileInfo()
            }
        })
        placeInput.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        findViewById<View>(R.id.btnLocation).setOnClickListener { detectPlace(userAsked = true) }
        switchAutoPlace.isChecked = Prefs.autoPlace(this)
        switchAutoPlace.setOnCheckedChangeListener { _, on ->
            Prefs.setAutoPlace(this, on)
            if (on) detectPlace(userAsked = true)
        }

        // Shortcuts
        switchListening.setOnCheckedChangeListener { _, on ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (on) {
                withPermissions { sendToService(EarTapService.ACTION_START, foreground = true) }
            } else if (EarTapService.isRunning) {
                sendToService(EarTapService.ACTION_STOP, foreground = false)
            }
        }
        switchVolume.isChecked = Prefs.volumeTrigger(this)
        switchVolume.setOnCheckedChangeListener { _, on ->
            Prefs.setVolumeTrigger(this, on)
            refreshService()
        }
        switchEarbud.isChecked = Prefs.earbudTrigger(this)
        spinnerTrigger.isEnabled = switchEarbud.isChecked
        switchEarbud.setOnCheckedChangeListener { _, on ->
            Prefs.setEarbudTrigger(this, on)
            spinnerTrigger.isEnabled = on
            refreshService()
        }
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, Prefs.TRIGGER_OPTIONS.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTrigger.adapter = adapter
        val current = Prefs.TRIGGER_OPTIONS.indexOfFirst { it.second == Prefs.triggerKey(this) }
        spinnerTrigger.setSelection(if (current >= 0) current else 0)
        spinnerTrigger.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setTriggerKey(this@MainActivity, Prefs.TRIGGER_OPTIONS[pos].second)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        switchEarbudMic.isChecked = Prefs.useEarbudMic(this)
        switchEarbudMic.setOnCheckedChangeListener { _, on -> Prefs.setUseEarbudMic(this, on) }

        findViewById<View>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
                toast("Open Battery and choose Unrestricted / Allow background activity")
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
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
        wasRecording = EarTapService.isRecording
        refreshUi()
        loadRecordings()
        handler.post(ticker)
        if (Prefs.autoPlace(this)) detectPlace(userAsked = false)
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        handler.removeCallbacks(ticker)
        stopPlayback()
        super.onStop()
    }

    // ---------------------------------------------------------------- volume down x3 inside the app

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && Prefs.volumeTrigger(this)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val am = getSystemService(AudioManager::class.java)
                EarTapService.lastKey = "Volume down"
                if (VolumeTrigger.onVolumeDown(am, showUi = true, isRepeat = event.repeatCount > 0)) {
                    withPermissions { sendToService(EarTapService.ACTION_TOGGLE, foreground = true) }
                }
                refreshUi()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- service

    private fun sendToService(action: String, foreground: Boolean) {
        val i = Intent(this, EarTapService::class.java).setAction(action)
        if (foreground) startForegroundService(i) else startService(i)
    }

    private fun refreshService() {
        if (EarTapService.isRunning) sendToService(EarTapService.ACTION_REFRESH, foreground = false)
        refreshUi()
    }

    private fun withPermissions(action: () -> Unit) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), REQ_START)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        when (code) {
            REQ_START -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    pendingAction?.invoke()
                } else {
                    toast("Microphone permission is needed to record")
                }
                pendingAction = null
                refreshUi()
                loadRecordings()
            }
            REQ_LOCATION -> {
                if (results.any { it == PackageManager.PERMISSION_GRANTED }) detectPlace(userAsked = true)
                else toast("Location not allowed — type the place instead")
            }
        }
    }

    // ---------------------------------------------------------------- UI state

    private fun refreshUi() {
        val running = EarTapService.isRunning
        val recording = EarTapService.isRecording
        val starting = EarTapService.isStarting

        when {
            recording -> setChip("● REC", R.drawable.bg_chip_rec, R.color.rec)
            running -> setChip("Ready", R.drawable.bg_chip_listening, R.color.success)
            else -> setChip("Off", R.drawable.bg_chip_idle, R.color.text_secondary)
        }

        recordIcon.setImageResource(if (recording || starting) R.drawable.ic_stop_white else R.drawable.ic_mic_white)
        recordLabel.text = when {
            recording -> "Recording ${EarTapService.currentTitle} · tap to stop"
            starting -> "Starting…"
            else -> "Tap to start recording"
        }
        timer.setTextColor(getColor(if (recording) R.color.rec else R.color.text_primary))
        if (recording) startPulse() else stopPulse()

        updatingUi = true
        switchListening.isChecked = running
        updatingUi = false

        lastTrigger.text = "Last shortcut received: ${EarTapService.lastKey}"
        updateFileInfo()
        updateTimer()
    }

    private fun setChip(text: String, bg: Int, color: Int) {
        statusChip.text = text
        statusChip.setBackgroundResource(bg)
        statusChip.setTextColor(getColor(color))
    }

    private fun updateFileInfo() {
        val now = Date()
        if (EarTapService.isRecording && EarTapService.currentFileName.isNotEmpty()) {
            nextFileLabel.text = "SAVING TO"
            nextFile.text = EarTapService.currentFileName
        } else {
            nextFileLabel.text = "NEXT FILE"
            nextFile.text = Naming.previewName(this, now)
        }
        folderPath.text = Naming.folderFor(now).trimEnd('/')
    }

    private fun updateTimer() {
        val start = EarTapService.recordingStartedAt
        if (!EarTapService.isRecording || start == 0L) {
            timer.text = "00:00:00"
            return
        }
        val s = (SystemClock.elapsedRealtime() - start) / 1000
        timer.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            recordPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.72f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.72f, 1.0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.0f)
        ).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        recordPulse.alpha = 0f
    }

    // ---------------------------------------------------------------- place from location

    @SuppressLint("MissingPermission")
    private fun detectPlace(userAsked: Boolean) {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            if (userAsked) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOCATION
                )
            }
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> {
                if (userAsked) toast("Turn on Location to detect the place")
                return
            }
        }
        if (userAsked) toast("Finding your location…")
        lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
            val l = loc ?: lm.getLastKnownLocation(provider)
            if (l == null) {
                if (userAsked) toast("Couldn't get your location — type the place instead")
            } else {
                resolvePlace(l.latitude, l.longitude, userAsked)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePlace(lat: Double, lon: Double, userAsked: Boolean) {
        Thread {
            val name: String? = try {
                Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
                    ?.firstOrNull()
                    ?.let { a -> a.subLocality ?: a.locality ?: a.subAdminArea ?: a.adminArea }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (!name.isNullOrBlank()) {
                    placeInput.setText(name)
                    placeInput.setSelection(placeInput.text.length)
                    if (userAsked) toast("Place set to $name")
                } else if (userAsked) {
                    toast("Couldn't find a place name — type it instead")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- recordings list

    private fun loadRecordings() {
        val list = mutableListOf<Rec>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
        )
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "(${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)",
                arrayOf("${Naming.root}/%", "Music/EarTap/%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                    )
                    list += Rec(uri, c.getString(nameCol) ?: "", c.getLong(durCol), c.getLong(addedCol))
                }
            }
        } catch (e: Exception) {
            toast("Could not list recordings: ${e.message}")
        }
        render(list)
    }

    private fun render(list: List<Rec>) {
        recordingsContainer.removeAllViews()
        playIcons.clear()
        emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recordingsCount.text = when (list.size) {
            0 -> ""
            1 -> "1 recording"
            else -> "${list.size} recordings"
        }

        var currentDay = ""
        var card: LinearLayout? = null
        for (rec in list) {
            val day = dayLabel(rec.addedSec)
            var target = card
            if (target == null || day != currentDay) {
                currentDay = day
                val header = layoutInflater.inflate(R.layout.item_day_header, recordingsContainer, false) as TextView
                header.text = day
                recordingsContainer.addView(header)
                target = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    clipToOutline = true
                }
                recordingsContainer.addView(
                    target,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                card = target
            } else {
                val divider = View(this).apply { setBackgroundColor(getColor(R.color.outline)) }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                lp.marginStart = dp(68)
                target.addView(divider, lp)
            }
            val row = layoutInflater.inflate(R.layout.item_recording, target, false)
            bindRow(row, rec)
            target.addView(row)
        }
    }

    private fun bindRow(row: View, rec: Rec) {
        val p = Naming.parse(rec.name)
        val title = row.findViewById<TextView>(R.id.title)
        val subtitle = row.findViewById<TextView>(R.id.subtitle)
        val playIcon = row.findViewById<ImageView>(R.id.playIcon)
        val btnMore = row.findViewById<View>(R.id.btnMore)

        title.text = if (p != null) "Interaction ${p.number}" else rec.name.removeSuffix(".m4a")
        val time = p?.time ?: SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(rec.addedSec * 1000))
        subtitle.text = listOfNotNull(time, p?.place, formatDuration(rec.durationMs)).joinToString("  ·  ")

        playIcons[rec.uri] = playIcon
        playIcon.setImageResource(if (rec.uri == playingUri) R.drawable.ic_pause else R.drawable.ic_play)
        row.findViewById<View>(R.id.btnPlay).setOnClickListener { togglePlayback(rec.uri) }
        row.setOnClickListener { togglePlayback(rec.uri) }
        btnMore.setOnClickListener { showMenu(btnMore, rec) }
        row.setOnLongClickListener {
            showMenu(btnMore, rec)
            true
        }
    }

    private fun dayLabel(addedSec: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = addedSec * 1000 }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val pretty = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(cal.time)
        return when {
            sameDay(cal, today) -> "Today · $pretty"
            sameDay(cal, yesterday) -> "Yesterday · $pretty"
            else -> pretty
        }
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun formatDuration(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
               else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- playback / share / delete

    private fun togglePlayback(uri: Uri) {
        if (playingUri == uri) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingUri = uri
        } catch (e: Exception) {
            stopPlayback()
            toast("Can't play: ${e.message}")
        }
        updatePlayIcons()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playingUri = null
        updatePlayIcons()
    }

    private fun updatePlayIcons() {
        for ((uri, icon) in playIcons) {
            icon.setImageResource(if (uri == playingUri) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun showMenu(anchor: View, rec: Rec) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Share")
            menu.add(0, 2, 1, "Delete")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> share(rec)
                    2 -> delete(rec)
                }
                true
            }
            show()
        }
    }

    private fun share(rec: Rec) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, rec.uri)
            .putExtra(Intent.EXTRA_SUBJECT, rec.name.removeSuffix(".m4a"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share recording"))
    }

    private fun delete(rec: Rec) {
        if (playingUri == rec.uri) stopPlayback()
        try {
            // Android shows its own "Allow EarTap to delete?" confirmation
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(rec.uri))
            startIntentSenderForResult(request.intentSender, REQ_DELETE, null, 0, 0, 0)
        } catch (e: Exception) {
            toast("Couldn't delete — remove it from your Files app")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) loadRecordings()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
