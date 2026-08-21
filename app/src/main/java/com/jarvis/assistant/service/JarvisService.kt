package com.jarvis.assistant.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.CommandRouter
import com.jarvis.assistant.ai.MediaPipeLlmEngine
import com.jarvis.assistant.ai.PiperTtsEngine
import com.jarvis.assistant.ai.VoiceEngine
import com.jarvis.assistant.core.JarvisApplication
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.ModelProvisioner
import com.jarvis.assistant.core.VisualizerMode
import com.jarvis.assistant.files.FileToolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * The always-on brain of the app. Runs as a foreground service with a silent,
 * ongoing notification (required by Android to keep a mic-using process alive
 * indefinitely). State machine:
 *
 *   IDLE (wake-word spotting) -> LISTENING (command capture) -> THINKING (LLM)
 *   -> SPEAKING (TTS) -> back to IDLE
 */
class JarvisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var commandTimeoutJob: Job? = null

    private lateinit var voiceEngine: VoiceEngine
    private lateinit var llm: MediaPipeLlmEngine
    private lateinit var fileTools: FileToolManager
    private lateinit var router: CommandRouter
    private lateinit var provisioner: ModelProvisioner
    private var piperTts: PiperTtsEngine? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var usingPiper = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Setting up…"))
        acquireWakeLock()

        fileTools = FileToolManager(this)
        router = CommandRouter(fileTools)
        llm = MediaPipeLlmEngine(this)
        provisioner = ModelProvisioner(this)

        initTts() // native TTS is always initialized as a guaranteed fallback

        // Optional Piper voice for a real-amplitude waveform instead of the synthetic
        // envelope. If assets/piper-voice/ isn't bundled, we silently stay on native TTS.
        val piperDir = File(filesDir, "models/piper-voice")
        if (File(piperDir, "voice.onnx").exists()) {
            runCatching {
                piperTts = PiperTtsEngine(piperDir).apply { load() }
                usingPiper = true
            }
        }

        scope.launch {
            // One-time unpack of bundled assets (Vosk zip + LLM model) into filesDir.
            provisioner.provision { progress ->
                when (progress) {
                    is ModelProvisioner.Progress.Status -> updateNotification(progress.message)
                    is ModelProvisioner.Progress.Error -> updateNotification(progress.message)
                    is ModelProvisioner.Progress.Done -> Unit
                }
            }

            if (!provisioner.isFullyProvisioned()) {
                updateNotification("Setup incomplete — add model files to assets/, see README.md")
                return@launch
            }

            voiceEngine = VoiceEngine(provisioner.voskModelDir)

            runCatching {
                voiceEngine.loadModel()
                llm.load(provisioner.llmModelFile)
            }.onSuccess {
                beginIdleListening()
            }.onFailure { t ->
                updateNotification("Setup incomplete: ${t.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system kills this process under memory pressure,
        // Android recreates it and re-delivers onStartCommand so listening resumes.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::voiceEngine.isInitialized) voiceEngine.release()
        llm.unload()
        piperTts?.release()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // --- State machine --------------------------------------------------------

    private fun beginIdleListening() {
        JarvisState.setMode(VisualizerMode.IDLE)
        updateNotification("Listening for \"Jarvis\"…")
        voiceEngine.startWakeWordListening(object : VoiceEngine.Callbacks {
            override fun onWakeWordDetected() = onWakeWord()
            override fun onPartialAmplitude(level: Float) {
                if (JarvisState.mode.value == VisualizerMode.IDLE) JarvisState.pushAmplitude(level * 0.3f)
            }
            override fun onCommandFinal(text: String) { /* not used in wake mode */ }
            override fun onCommandTimeout() { /* not used in wake mode */ }
            override fun onError(t: Throwable) { beginIdleListening() /* self-heal & retry */ }
        })
    }

    private fun onWakeWord() {
        voiceEngine.stopWakeWordListening()
        JarvisState.setMode(VisualizerMode.LISTENING)
        updateNotification("Listening…")
        playChime()

        commandTimeoutJob = scope.launch {
            delay(8_000)
            voiceEngine.stopCommandCapture()
            beginIdleListening()
        }

        voiceEngine.startCommandCapture(object : VoiceEngine.Callbacks {
            override fun onWakeWordDetected() { /* not relevant here */ }
            override fun onPartialAmplitude(level: Float) = JarvisState.pushAmplitude(level)
            override fun onCommandFinal(text: String) {
                commandTimeoutJob?.cancel()
                voiceEngine.stopCommandCapture()
                onCommandCaptured(text)
            }
            override fun onCommandTimeout() {
                commandTimeoutJob?.cancel()
                beginIdleListening()
            }
            override fun onError(t: Throwable) {
                commandTimeoutJob?.cancel()
                beginIdleListening()
            }
        })
    }

    private fun onCommandCaptured(text: String) {
        JarvisState.pushTranscript("You: $text")
        JarvisState.setMode(VisualizerMode.THINKING)
        updateNotification("Thinking…")

        scope.launch {
            val reply = runCatching {
                when (val result = router.route(text)) {
                    is CommandRouter.RouteResult.SpokenReply -> result.text
                    is CommandRouter.RouteResult.NeedsLlm -> llm.generate(result.prompt)
                }
            }.getOrElse { "I'm afraid I've hit a snag: ${it.message ?: "unknown error"}." }

            speak(reply)
        }
    }

    private fun speak(text: String) {
        JarvisState.pushTranscript("Jarvis: $text")
        JarvisState.setMode(VisualizerMode.SPEAKING)
        updateNotification("Speaking…")

        val piper = piperTts
        if (usingPiper && piper != null && piper.isLoaded()) {
            scope.launch {
                piper.speak(
                    text = text,
                    onAmplitude = { level -> JarvisState.pushAmplitude(level) },
                    onDone = { beginIdleListening() }
                )
            }
        } else {
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    // --- TTS --------------------------------------------------------------------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Prefer British English to match the butler persona; fall back gracefully.
                val result = tts?.setLanguage(Locale.UK)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setPitch(0.95f)
                tts?.setSpeechRate(1.0f)
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Drive a synthetic speech envelope for the visualizer since Android's
                // TTS API doesn't expose raw output amplitude.
                scope.launch { animateSpeakingEnvelope() }
            }
            override fun onDone(utteranceId: String?) {
                beginIdleListening()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                beginIdleListening()
            }
        })
    }

    /** Synthetic amplitude wobble so the ring animates plausibly while TTS speaks. */
    private suspend fun animateSpeakingEnvelope() {
        while (JarvisState.mode.value == VisualizerMode.SPEAKING) {
            val level = 0.35f + (Math.random().toFloat() * 0.55f)
            JarvisState.pushAmplitude(level)
            delay(90)
        }
    }

    // --- Notification / lifecycle plumbing ---------------------------------------

    private fun buildNotification(status: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_jarvis_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun playChime() { /* optional: play a short earcon via SoundPool on wake-word detection */ }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::ListenerWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // renewed periodically; partial lock only keeps CPU alive, not the screen
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}

private fun CoroutineScope.cancel() {
    (coroutineContext[Job])?.cancel()
}
