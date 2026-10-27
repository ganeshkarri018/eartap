package com.eartap.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import java.util.Date

/**
 * Runs in the background and toggles a recording when a shortcut arrives:
 *
 *   earbud tap      -> Bluetooth media command -> MediaSession callback  -> toggle()
 *   volume down x3  -> Android volume keys     -> MediaSession volume    -> toggle()
 *
 * Both work because EarTap keeps an inaudible sound playing, which makes Android
 * send media buttons and volume keys to EarTap first.
 */
class EarTapService : Service() {

    companion object {
        const val ACTION_START = "com.eartap.recorder.START"
        const val ACTION_STOP = "com.eartap.recorder.STOP"
        const val ACTION_TOGGLE = "com.eartap.recorder.TOGGLE"
        const val ACTION_REFRESH = "com.eartap.recorder.REFRESH"
        const val BROADCAST_STATE = "com.eartap.recorder.STATE"
        private const val CHANNEL_ID = "eartap"
        private const val NOTIF_ID = 1
        private const val TAG = "EarTap"
        private const val DEBOUNCE_MS = 700L

        @Volatile var isRunning = false
        @Volatile var isRecording = false
        @Volatile var isStarting = false
        @Volatile var lastKey = "none yet"
        /** SystemClock.elapsedRealtime() when the current recording started, 0 if none. */
        @Volatile var recordingStartedAt = 0L
        @Volatile var currentTitle = ""
        @Volatile var currentFileName = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSession

    private var recorder: MediaRecorder? = null
    private var currentUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var tone: ToneGenerator? = null
    private var silentTrack: AudioTrack? = null
    private var lastToggleAt = 0L
    private var usingSco = false
    private var pendingStart: Runnable? = null
    private var recordingWallStart = 0L

    /** Receives the hardware volume keys while EarTap is the active media app. */
    private val volumeProvider =
        object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                handler.post { onVolumeKey(direction) }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        session = MediaSession(this, "EarTap").apply {
            setCallback(sessionCallback, handler)
            setPlaybackState(playingState())
            isActive = true
        }
        applyVolumeMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                stopRecording()
                stopSilentLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                broadcastState()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                if (isRunning) {
                    applyVolumeMode()
                    updateNotification()
                } else {
                    stopSelf()
                }
                broadcastState()
                return START_NOT_STICKY
            }
        }

        if (!isRunning) {
            if (!goForeground()) return START_NOT_STICKY
            isRunning = true
        }
        claimMediaButtons()
        if (intent?.action == ACTION_TOGGLE) toggle()
        broadcastState()
        // NOT_STICKY: Android doesn't allow restarting a mic service from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopRecording()
        stopSilentLoop()
        session.isActive = false
        session.release()
        tone?.release()
        tone = null
        broadcastState()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- shortcuts

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (ev == null) return super.onMediaButtonEvent(mediaButtonIntent)

            val firstDown = ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0
            if (firstDown) {
                lastKey = describeKey(ev.keyCode)
                broadcastState()
            }
            if (Prefs.earbudTrigger(this@EarTapService) && isTrigger(ev.keyCode)) {
                if (firstDown) toggle()
                return true // swallow both DOWN and UP
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
        }
    }

    private fun isTrigger(keyCode: Int): Boolean {
        val wanted = Prefs.triggerKey(this)
        if (wanted == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            // Earbuds send play/pause in several flavours
            return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        }
        return keyCode == wanted
    }

    private fun onVolumeKey(direction: Int) {
        when {
            direction < 0 -> {
                lastKey = "Volume down"
                if (VolumeTrigger.onVolumeDown(audioManager, showUi = true)) toggle()
            }
            direction > 0 -> {
                lastKey = "Volume up"
                try {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                    )
                } catch (_: Exception) {
                }
            }
            else -> return
        }
        broadcastState()
    }

    /** Volume shortcut on: EarTap receives the volume keys. Off: keys behave as normal. */
    private fun applyVolumeMode() {
        if (Prefs.volumeTrigger(this)) {
            session.setPlaybackToRemote(volumeProvider)
        } else {
            session.setPlaybackToLocal(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
        }
    }

    private fun playingState(): PlaybackState =
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
            .build()

    /**
     * Android sends earbud buttons and volume keys to the app that is playing audio
     * right now. So EarTap plays an inaudible looping sound the whole time it listens.
     */
    private fun claimMediaButtons() {
        session.setPlaybackState(playingState())
        session.isActive = true
        if (silentTrack != null) return
        try {
            val rate = 8000
            val samples = rate
            val data = ShortArray(samples) { i -> if (i % 2 == 0) 1.toShort() else (-1).toShort() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, samples)
            track.setLoopPoints(0, samples, -1)
            track.play()
            silentTrack = track
        } catch (e: Exception) {
            Log.w(TAG, "Silent playback failed", e)
        }
    }

    private fun stopSilentLoop() {
        try { silentTrack?.stop() } catch (_: Exception) {}
        try { silentTrack?.release() } catch (_: Exception) {}
        silentTrack = null
    }

    // ---------------------------------------------------------------- recording

    private fun toggle() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt < DEBOUNCE_MS) return
        lastToggleAt = now
        if (isRecording || pendingStart != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            signalError()
            return
        }
        signalStart()

        val earbudMic = Prefs.useEarbudMic(this) && routeToEarbudMic()
        val source = if (earbudMic) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                     else MediaRecorder.AudioSource.MIC
        // Wait for the beep to finish (and for the earbud mic link, which takes ~1.5 s)
        val r = Runnable {
            pendingStart = null
            isStarting = false
            beginRecorder(source, earbudMic)
        }
        pendingStart = r
        isStarting = true
        handler.postDelayed(r, if (earbudMic) 1500L else 350L)
        updateNotification()
        broadcastState()
    }

    private fun beginRecorder(source: Int, earbudMic: Boolean) {
        val now = Date()
        val number = Naming.nextNumber(this, now)
        val name = Naming.fileName(number, now, Prefs.place(this))
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, name.removeSuffix(".m4a"))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Naming.folderFor(now))
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
                else @Suppress("DEPRECATION") MediaRecorder()
        try {
            uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open file")

            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Bluetooth mic is low bandwidth (16 kHz); phone mic can do 44.1 kHz
            r.setAudioSamplingRate(if (earbudMic) 16000 else 44100)
            r.setAudioEncodingBitRate(if (earbudMic) 32000 else 128000)
            r.setOutputFile(pfd.fileDescriptor)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            r.release()
            try { pfd?.close() } catch (_: Exception) {}
            uri?.let { contentResolver.delete(it, null, null) }
            releaseSco()
            isRecording = false
            signalError()
            updateNotification()
            broadcastState()
            return
        }

        Prefs.setLastNumber(this, Naming.dayKey(now), number)
        recorder = r
        currentUri = uri
        currentPfd = pfd
        currentTitle = "Interaction $number"
        currentFileName = name
        recordingStartedAt = SystemClock.elapsedRealtime()
        recordingWallStart = System.currentTimeMillis()
        isRecording = true
        updateNotification()
        broadcastState()
    }

    private fun stopRecording() {
        pendingStart?.let { handler.removeCallbacks(it) }
        val wasActive = isRecording || pendingStart != null
        pendingStart = null
        isStarting = false

        val r = recorder
        recorder = null
        var saved = false
        if (r != null) {
            try {
                r.stop()
                saved = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Recording too short or failed", e) // stop() right after start() throws
            }
            r.release()
        }
        try { currentPfd?.close() } catch (_: Exception) {}
        currentPfd = null

        currentUri?.let { uri ->
            if (saved) {
                val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                contentResolver.update(uri, v, null, null)
            } else {
                contentResolver.delete(uri, null, null)
            }
        }
        currentUri = null
        isRecording = false
        recordingStartedAt = 0L
        currentTitle = ""
        currentFileName = ""
        releaseSco()

        if (saved) signalSaved() else if (wasActive) signalError()
        if (isRunning) {
            claimMediaButtons()
            updateNotification()
        }
        broadcastState()
    }

    // ---------------------------------------------------------------- earbud mic (optional)

    private fun routeToEarbudMic(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                } ?: return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                usingSco = audioManager.setCommunicationDevice(dev)
                if (!usingSco) audioManager.mode = AudioManager.MODE_NORMAL
                usingSco
            } else {
                if (!audioManager.isBluetoothScoAvailableOffCall) return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION") audioManager.startBluetoothSco()
                @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
                usingSco = true
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Earbud mic not available, using phone mic", e)
            audioManager.mode = AudioManager.MODE_NORMAL
            usingSco = false
            false
        }
    }

    private fun releaseSco() {
        if (!usingSco) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        usingSco = false
    }

    // ---------------------------------------------------------------- feedback
    // Vibration always. Beeps only when earbuds / headphones are connected, so the
    // phone never beeps out loud in a meeting.

    private fun signalStart() {
        vibrate(longArrayOf(0, 120))
        beep(ToneGenerator.TONE_PROP_BEEP)
    }

    private fun signalSaved() {
        vibrate(longArrayOf(0, 80, 120, 80))
        beep(ToneGenerator.TONE_PROP_BEEP2)
    }

    private fun signalError() {
        vibrate(longArrayOf(0, 400))
        beep(ToneGenerator.TONE_PROP_NACK)
    }

    private fun headphonesConnected(): Boolean = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (_: Exception) {
        false
    }

    private fun beep(toneType: Int) {
        if (!headphonesConnected()) return
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            tg.startTone(toneType, 300)
        } catch (e: Exception) {
            Log.w(TAG, "Beep failed", e)
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "EarTap", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows that EarTap is ready to record"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun goForeground(): Boolean {
        return try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            true
        } catch (e: Exception) {
            // Happens if Android refuses to start the mic service from the background
            Log.e(TAG, "Could not start foreground service", e)
            stopSelf()
            false
        }
    }

    private fun shortcutsText(): String {
        val parts = mutableListOf<String>()
        if (Prefs.volumeTrigger(this)) parts += "volume down ×3"
        if (Prefs.earbudTrigger(this)) parts += "earbud tap"
        return if (parts.isEmpty()) "Tap Record to start"
               else "Start with " + parts.joinToString(" or ")
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, EarTapService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val offIntent = PendingIntent.getService(
            this, 2, Intent(this, EarTapService::class.java).setAction(ACTION_STOP), flags
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_mic_white)

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setColor(getColor(if (isRecording) R.color.rec else R.color.primary))

        when {
            isRecording -> b
                .setContentTitle("● Recording · $currentTitle")
                .setContentText("${Prefs.place(this)} · volume down ×3 or Stop to finish")
                .setUsesChronometer(true)
                .setWhen(recordingWallStart)
                .setShowWhen(true)
            pendingStart != null -> b
                .setContentTitle("Starting recording…")
                .setShowWhen(false)
            else -> b
                .setContentTitle("EarTap is ready")
                .setContentText(shortcutsText())
                .setShowWhen(false)
        }

        return b
            .addAction(
                Notification.Action.Builder(
                    icon, if (isRecording) "Stop" else "Record", toggleIntent
                ).build()
            )
            .addAction(Notification.Action.Builder(icon, "Turn off", offIntent).build())
            .build()
    }

    private fun updateNotification() {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun broadcastState() {
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName))
    }
}
