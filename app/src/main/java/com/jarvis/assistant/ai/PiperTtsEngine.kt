package com.jarvis.assistant.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Piper voice via sherpa-onnx's OfflineTts (Piper models are VITS-family and
 * load directly through sherpa-onnx's VITS config — no separate Piper
 * runtime needed on Android). Unlike Android's built-in TextToSpeech, this
 * gives us the raw PCM samples, so the visualizer can react to *real*
 * amplitude while Jarvis talks instead of a synthetic wobble.
 *
 * Model files: a Piper .onnx voice + its tokens.txt + espeak-ng-data/,
 * typically ~60MB for a standard-quality English voice. See README.md.
 */
class PiperTtsEngine(private val modelDir: File) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 22050

    fun load() {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(modelDir, "voice.onnx").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                debug = false
            )
        )
        tts = OfflineTts(config = config)
    }

    fun isLoaded(): Boolean = tts != null

    /**
     * Synthesizes and plays [text], invoking [onAmplitude] with a real 0f..1f
     * RMS level for each ~40ms chunk as it's played, and [onDone] once playback
     * finishes. Runs on Dispatchers.IO; safe to call from a coroutine scope.
     */
    suspend fun speak(text: String, onAmplitude: (Float) -> Unit, onDone: () -> Unit) =
        withContext(Dispatchers.IO) {
            val engine = tts ?: return@withContext onDone()
            val audio = engine.generate(text = text, sid = 0, speed = 1.0f)
            playWithAmplitudeCallback(audio.samples, onAmplitude)
            onDone()
        }

    private fun playWithAmplitudeCallback(samples: FloatArray, onAmplitude: (Float) -> Unit) {
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(min(minBufSize, samples.size * 4).coerceAtLeast(minBufSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        // Stream in chunks so we can report amplitude in near-real-time as it plays,
        // rather than computing it all up front and hoping playback timing matches.
        val chunkSize = (sampleRate * 0.04).toInt() // ~40ms chunks
        var offset = 0
        while (offset < samples.size) {
            val end = min(offset + chunkSize, samples.size)
            val chunk = samples.copyOfRange(offset, end)
            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            onAmplitude(rms(chunk))
            offset = end
        }
        track.stop()
        track.release()
        audioTrack = null
    }

    fun stop() {
        audioTrack?.let { runCatching { it.stop() } }
        audioTrack?.release()
        audioTrack = null
    }

    private fun rms(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0f
        for (s in chunk) sum += abs(s)
        val avg = sum / chunk.size
        // Piper output is quiet-normalized; scale up for a visually punchy ring.
        return (avg * 6f).coerceIn(0f, 1f)
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
    }
}
