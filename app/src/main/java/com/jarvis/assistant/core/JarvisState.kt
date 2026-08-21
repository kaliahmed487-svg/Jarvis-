package com.jarvis.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single in-process source of truth shared between JarvisService (producer)
 * and MainActivity / AudioVisualizerView (consumer). Service and Activity run
 * in the same process, so a plain singleton + StateFlow is sufficient — no
 * Binder/Messenger plumbing needed.
 */
enum class VisualizerMode {
    IDLE,       // breathing glow, waiting for wake word
    LISTENING,  // actively capturing a command, reacts to mic amplitude
    THINKING,   // command captured, LLM generating a response
    SPEAKING    // TTS is talking, reacts to synthetic speech envelope
}

object JarvisState {
    private val _mode = MutableStateFlow(VisualizerMode.IDLE)
    val mode = _mode.asStateFlow()

    /** Normalized 0f..1f input level, updated ~30-60x/sec while LISTENING or SPEAKING. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    /** Last thing the user said / last thing Jarvis said, useful for debugging or a future log view. */
    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript = _lastTranscript.asStateFlow()

    fun setMode(mode: VisualizerMode) {
        _mode.value = mode
    }

    fun pushAmplitude(level: Float) {
        _amplitude.value = level.coerceIn(0f, 1f)
    }

    fun pushTranscript(text: String) {
        _lastTranscript.value = text
    }
}
