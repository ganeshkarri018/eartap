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
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs in the background, listens for the earbud button command
 * (triple tap -> "previous track") and toggles a voice recording.
 *
 * Flow:  earbud tap -> Bluetooth AVRCP command -> Android -> our MediaSession
 *        -> toggle() -> MediaRecorder start/stop -> file in Music/EarTap
 */
class EarTapService : Service() {

    companion object {
        const val ACTION_START = "com.eartap.recorder.START"
        const val ACTION_STOP = "com.eartap.recorder.STOP"
        const val ACTION_TOGGLE = "com.eartap.recorder.TOGGLE"
        const val BROADCAST_STATE = "com.eartap.recorder.STATE"
        private const val CHANNEL_ID = "eartap"
        private const val NOTIF_ID = 1
        private const val TAG = "EarTap"
        private const val DEBOUNCE_MS = 700L

        @Volatile var isRunning = false
        @Volatile var isRecording = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSession

    private var recorder: MediaRecorder? = null
    private var currentUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var tone: ToneGenerator? = null
    private var lastToggleAt = 0L
    private var usingSco = false
    private var pendingStart: Runnable? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) return START_NOT_STICKY

        isRunning = true
        if (intent?.action == ACTION_TOGGLE) toggle() else claimMediaButtons()
        broadcastState()
        // NOT_STICKY: Android doesn't allow restarting a mic service from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopRecording()
        session.isActive = false
        session.release()
        tone?.release()
        tone = null
        broadcastState()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- earbud buttons

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (ev != null && isTrigger(ev.keyCode)) {
                Log.d(TAG, "Trigger key ${KeyEvent.keyCodeToString(ev.keyCode)} action=${ev.action}")
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) toggle()
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
     * Android sends earbud buttons to the app that played audio most recently.
     * Playing half a second of silence makes EarTap that app.
     */
    private fun claimMediaButtons() {
        session.setPlaybackState(playingState())
        session.isActive = true
        try {
            val rate = 8000
            val samples = rate / 2
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
            track.write(ShortArray(samples), 0, samples)
            track.play()
            handler.postDelayed({
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "Silent playback failed", e)
        }
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
            beep(error = true)
            return
        }
        beep(times = 1)
        vibrate(80)

        val earbudMic = Prefs.useEarbudMic(this) && routeToEarbudMic()
        val source = if (earbudMic) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                     else MediaRecorder.AudioSource.MIC
        // Wait for the beep to finish (and for the earbud mic link, which takes ~1.5 s)
        val r = Runnable {
            pendingStart = null
            beginRecorder(source, earbudMic)
        }
        pendingStart = r
        handler.postDelayed(r, if (earbudMic) 1500L else 350L)
        updateNotification()
    }

    private fun beginRecorder(source: Int, earbudMic: Boolean) {
        val name = "EarTap_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/EarTap")
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
            r.setAudioEncodingBitRate(if (earbudMic) 32000 else 96000)
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
            beep(error = true)
            updateNotification()
            broadcastState()
            return
        }

        recorder = r
        currentUri = uri
        currentPfd = pfd
        isRecording = true
        updateNotification()
        broadcastState()
    }

    private fun stopRecording() {
        pendingStart?.let { handler.removeCallbacks(it) }
        val wasActive = isRecording || pendingStart != null
        pendingStart = null

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
        releaseSco()

        if (wasActive) {
            beep(times = 2)
            vibrate(200)
        }
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

    /** 1 beep = started, 2 beeps = saved, low tone = error. You hear it in the earbuds. */
    private fun beep(times: Int = 1, error: Boolean = false) {
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            val t = when {
                error -> ToneGenerator.TONE_PROP_NACK
                times == 1 -> ToneGenerator.TONE_PROP_BEEP
                else -> ToneGenerator.TONE_PROP_BEEP2
            }
            tg.startTone(t, 300)
        } catch (e: Exception) {
            Log.w(TAG, "Beep failed", e)
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "EarTap", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows that EarTap is listening for earbud taps"
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
        val icon = Icon.createWithResource(this, R.drawable.ic_mic)
        val text = when {
            isRecording -> "● Recording… tap earbuds again to stop"
            pendingStart != null -> "Starting…"
            else -> "Listening — tap earbuds to record"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("EarTap")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    icon, if (isRecording) "Stop recording" else "Record now", toggleIntent
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
