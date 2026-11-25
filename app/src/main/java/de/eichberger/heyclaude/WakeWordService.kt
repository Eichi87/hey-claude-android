package de.eichberger.heyclaude

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import de.eichberger.heyclaude.ServiceStatus.State
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Vordergrunddienst (Typ „microphone“), der offline auf „Hey Claude“ lauscht.
 *
 * Ablauf bei Erkennung:
 *  1. Mikrofon sofort freigeben (AudioRecord stoppen und freigeben).
 *  2. Kurz vibrieren und die Claude-App öffnen (möglichst im Sprachmodus, siehe ClaudeLauncher).
 *  3. Erst wieder lauschen, wenn mindestens 5 s vergangen sind UND keine andere App mehr
 *     das Mikrofon nutzt (AudioManager.registerAudioRecordingCallback).
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    @Volatile
    private var running = false
    private var captureThread: Thread? = null

    /** Wird nur im Aufnahme-Thread benutzt; Threads laufen nie gleichzeitig. */
    private var detector: WakeWordDetector? = null

    @Volatile
    private var threshold = Prefs.DEFAULT_THRESHOLD
    private var detectedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val resumeCheck = Runnable { checkResume() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_THRESHOLD) threshold = Prefs.threshold(this)
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            // Während wir warten, ist unsere eigene Aufnahme beendet: Jede Konfiguration gehört
            // zu einer anderen App. Ist die Liste leer, ist das Mikrofon frei.
            if (ServiceStatus.state == State.WAITING && configs.isEmpty()) {
                main.removeCallbacks(resumeCheck)
                main.postDelayed(resumeCheck, MIC_FREE_DEBOUNCE_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioRecordingCallback(recordingCallback, main)
        threshold = Prefs.threshold(this)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> togglePause()
            else -> handleStart() // ACTION_START oder Neustart durch das System (intent == null)
        }
        return START_STICKY
    }

    private fun handleStart() {
        if (!hasMicPermission()) {
            // Ohne Mikrofon-Berechtigung lässt Android 14+ keinen Mikrofon-Dienst zu.
            ServiceStatus.set(State.ERROR, getString(R.string.status_no_mic_permission))
            stopSelf()
            return
        }
        if (!goForeground()) {
            // Android hat den Mikrofon-Dienst aus dem Hintergrund nicht erlaubt.
            ServiceStatus.set(State.ERROR, getString(R.string.status_not_allowed))
            Notifications.showActivatePrompt(this)
            stopSelf()
            return
        }
        Notifications.cancelActivatePrompt(this)
        when (ServiceStatus.state) {
            State.LISTENING, State.STARTING, State.WAITING, State.PAUSED -> updateNotification()
            else -> startListening()
        }
    }

    private fun goForeground(): Boolean = try {
        val n = Notifications.service(this, State.STARTING, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notifications.SERVICE_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground fehlgeschlagen", e)
        false
    }

    private fun startListening() {
        if (captureThread?.isAlive == true) return
        main.removeCallbacks(resumeCheck)
        running = true
        acquireWakeLock()
        ServiceStatus.set(State.STARTING)
        updateNotification()
        captureThread = thread(name = "wakeword-capture") { captureLoop() }
    }

    private fun stopListening() {
        running = false
        captureThread?.join(JOIN_TIMEOUT_MS)
        captureThread = null
        releaseWakeLock()
    }

    @SuppressLint("MissingPermission") // wird in handleStart() geprüft
    private fun captureLoop() {
        val det = try {
            detector ?: WakeWordDetector(applicationContext).also { detector = it }
        } catch (e: Exception) {
            Log.e(TAG, "Modelle konnten nicht geladen werden", e)
            postError(getString(R.string.status_model_error, e.message ?: e.javaClass.simpleName))
            return
        }
        det.reset()

        val minBuffer = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeWordDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, WakeWordDetector.CHUNK_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            postError(getString(R.string.status_mic_unavailable))
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            postError(getString(R.string.status_mic_unavailable))
            return
        }

        var detected = false
        try {
            record.startRecording()
            main.post {
                if (running) {
                    ServiceStatus.set(State.LISTENING)
                    updateNotification()
                }
            }
            val buffer = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
            var peak = 0f
            var peakLevel = -100f
            var peakFrames = 0
            ServiceStatus.maxScore = 0f
            while (running) {
                var offset = 0
                while (offset < buffer.size && running) {
                    val n = record.read(buffer, offset, buffer.size - offset)
                    when {
                        n > 0 -> offset += n
                        n == 0 -> Thread.sleep(5)
                        else -> throw IllegalStateException("AudioRecord.read() = $n")
                    }
                }
                if (!running) break

                val score = det.process(buffer)
                peak = max(peak, score)
                peakLevel = max(peakLevel, levelDb(buffer))
                ServiceStatus.maxScore = max(ServiceStatus.maxScore, score)
                if (++peakFrames >= FRAMES_PER_SECOND) {
                    ServiceStatus.recentScore = peak
                    ServiceStatus.recentLevelDb = peakLevel
                    peak = 0f
                    peakLevel = -100f
                    peakFrames = 0
                }
                if (score >= threshold) {
                    ServiceStatus.recentScore = score
                    detected = true
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Aufnahmefehler", e)
            if (running) postError(getString(R.string.status_mic_unavailable))
        } finally {
            // Mikrofon SOFORT freigeben, noch bevor der Assistent gestartet wird.
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
        running = false
        if (detected) main.post { onWakeWord() }
    }

    /** Effektivwert eines Blocks in dBFS; 0 dB = Vollaussteuerung, -100 = Stille. */
    private fun levelDb(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        val rms = kotlin.math.sqrt(sum / samples.size)
        return if (rms < 1.0) -100f else (20.0 * kotlin.math.log10(rms / 32768.0)).toFloat()
    }

    private fun onWakeWord() {
        captureThread = null
        releaseWakeLock()
        if (ServiceStatus.state != State.LISTENING) return // inzwischen pausiert oder gestoppt

        vibrate()
        detectedAt = SystemClock.elapsedRealtime()
        val launched = ClaudeLauncher.launch(this)
        ServiceStatus.set(
            State.WAITING,
            if (launched != null) {
                getString(R.string.status_launched, launched.stage)
            } else {
                getString(R.string.status_no_claude)
            },
        )
        updateNotification()
        main.postDelayed(resumeCheck, MIN_PAUSE_MS)
    }

    private fun checkResume() {
        if (ServiceStatus.state != State.WAITING) return
        val elapsed = SystemClock.elapsedRealtime() - detectedAt
        if (elapsed < MIN_PAUSE_MS) {
            main.postDelayed(resumeCheck, MIN_PAUSE_MS - elapsed)
            return
        }
        if (audioManager.activeRecordingConfigurations.isNotEmpty()) {
            // Eine andere App (z. B. Claude) nimmt noch auf. Der Callback meldet das Ende;
            // zur Sicherheit prüfen wir zusätzlich regelmäßig nach.
            main.postDelayed(resumeCheck, SAFETY_RECHECK_MS)
            return
        }
        startListening()
    }

    private fun togglePause() {
        when (ServiceStatus.state) {
            State.PAUSED -> if (hasMicPermission()) startListening()
            State.STOPPED -> handleStart()
            else -> {
                main.removeCallbacks(resumeCheck)
                stopListening()
                ServiceStatus.set(State.PAUSED)
                updateNotification()
            }
        }
    }

    private fun shutdown() {
        main.removeCallbacks(resumeCheck)
        stopListening()
        ServiceStatus.set(State.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(resumeCheck)
        stopListening()
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        detector?.close()
        detector = null
        if (ServiceStatus.state != State.ERROR) ServiceStatus.set(State.STOPPED)
        super.onDestroy()
    }

    private fun postError(message: String) {
        running = false
        main.post {
            captureThread = null
            releaseWakeLock()
            ServiceStatus.set(State.ERROR, message)
            updateNotification()
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(
            Notifications.SERVICE_ID,
            Notifications.service(this, ServiceStatus.state, ServiceStatus.message),
        )
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @SuppressLint("WakelockTimeout") // Lauschen ist bewusst dauerhaft; wird beim Pausieren freigegeben.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyClaude:listening")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "WakeWordService"

        const val ACTION_START = "de.eichberger.heyclaude.START"
        const val ACTION_STOP = "de.eichberger.heyclaude.STOP"
        const val ACTION_TOGGLE_PAUSE = "de.eichberger.heyclaude.TOGGLE_PAUSE"

        private const val MIN_PAUSE_MS = 5_000L
        private const val MIC_FREE_DEBOUNCE_MS = 1_000L
        private const val SAFETY_RECHECK_MS = 2_000L
        private const val JOIN_TIMEOUT_MS = 500L
        private const val VIBRATION_MS = 120L
        private const val FRAMES_PER_SECOND = 12 // 12 x 80 ms ≈ 1 s

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
