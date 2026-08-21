package com.jarvis.assistant.ai

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Wraps Vosk for two jobs on the SAME small model:
 *  1. Always-on, low-power wake-word spotting ("jarvis")
 *  2. Full-vocabulary command transcription once woken
 *
 * Vosk was chosen over Sherpa-ONNX here for simplicity of the Android
 * SpeechService API (handles AudioRecord + VAD internally); swapping to
 * Sherpa-ONNX only requires re-implementing this one class.
 */
class VoiceEngine(private val modelDir: File) {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    interface Callbacks {
        fun onWakeWordDetected()
        fun onPartialAmplitude(level: Float)
        fun onCommandFinal(text: String)
        fun onCommandTimeout()
        fun onError(t: Throwable)
    }

    fun loadModel() {
        model = Model(modelDir.absolutePath)
    }

    /** Lightweight grammar-constrained recognizer — cheap CPU/battery cost for 24/7 listening. */
    fun startWakeWordListening(callbacks: Callbacks) {
        val m = model ?: return callbacks.onError(IllegalStateException("Model not loaded"))
        try {
            // Restricting the grammar to the wake phrase + [unk] keeps this recognizer fast & low-power.
            val recognizer = Recognizer(m, 16000f, """["jarvis", "[unk]"]""")
            speechService = SpeechService(recognizer, 16000f).apply {
                startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        hypothesis ?: return
                        callbacks.onPartialAmplitude(estimateAmplitude(hypothesis))
                        if (hypothesis.contains("jarvis", ignoreCase = true)) {
                            callbacks.onWakeWordDetected()
                        }
                    }
                    override fun onResult(hypothesis: String?) {
                        hypothesis ?: return
                        if (hypothesis.contains("jarvis", ignoreCase = true)) {
                            callbacks.onWakeWordDetected()
                        }
                    }
                    override fun onFinalResult(hypothesis: String?) { /* no-op in wake mode */ }
                    override fun onError(exception: Exception?) {
                        exception?.let { callbacks.onError(it) }
                    }
                    override fun onTimeout() { /* keep-alive; SpeechService restarts automatically */ }
                })
            }
        } catch (t: Throwable) {
            callbacks.onError(t)
        }
    }

    fun stopWakeWordListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    /** Open-vocabulary recognizer used right after the wake word fires. */
    fun startCommandCapture(callbacks: Callbacks) {
        val m = model ?: return callbacks.onError(IllegalStateException("Model not loaded"))
        try {
            val recognizer = Recognizer(m, 16000f) // no grammar restriction = full vocabulary
            speechService = SpeechService(recognizer, 16000f).apply {
                startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        hypothesis ?: return
                        callbacks.onPartialAmplitude(estimateAmplitude(hypothesis))
                    }
                    override fun onResult(hypothesis: String?) {
                        val text = extractText(hypothesis)
                        if (text.isNotBlank()) callbacks.onCommandFinal(text)
                    }
                    override fun onFinalResult(hypothesis: String?) {
                        val text = extractText(hypothesis)
                        if (text.isNotBlank()) callbacks.onCommandFinal(text) else callbacks.onCommandTimeout()
                    }
                    override fun onError(exception: Exception?) {
                        exception?.let { callbacks.onError(it) }
                    }
                    override fun onTimeout() {
                        callbacks.onCommandTimeout()
                    }
                })
            }
        } catch (t: Throwable) {
            callbacks.onError(t)
        }
    }

    fun stopCommandCapture() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    private fun extractText(hypothesisJson: String?): String {
        if (hypothesisJson == null) return ""
        return try {
            JSONObject(hypothesisJson).optString("text", "")
        } catch (e: Exception) {
            Log.w("VoiceEngine", "Failed to parse Vosk result", e)
            ""
        }
    }

    /**
     * Vosk's public API doesn't expose raw PCM amplitude alongside partials, so we
     * derive a rough "activity level" from partial-hypothesis growth as a cheap
     * proxy for driving the visualizer. Good enough for a UI cue, not for audio analysis.
     */
    private var lastPartialLen = 0
    private fun estimateAmplitude(partialJson: String): Float {
        val text = try { JSONObject(partialJson).optString("partial", "") } catch (e: Exception) { "" }
        val delta = (text.length - lastPartialLen).coerceIn(0, 10)
        lastPartialLen = text.length
        return (0.35f + delta * 0.06f).coerceIn(0.2f, 1f)
    }

    fun release() {
        stopWakeWordListening()
        stopCommandCapture()
        model = null
    }
}
