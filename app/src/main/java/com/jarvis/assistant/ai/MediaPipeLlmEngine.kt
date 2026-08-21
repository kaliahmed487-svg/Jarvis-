package com.jarvis.assistant.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Drop-in replacement for LlamaBridge that uses Google's official MediaPipe
 * "LLM Inference" API (`com.google.mediapipe:tasks-genai`) instead of a
 * hand-built llama.cpp JNI library. This is the "practical" trade-off:
 *
 *   - No NDK/CMake build, no native code to compile yourself.
 *   - Just point it at a `.task` bundle (Gemma 3 1B IT / Gemma 2 2B IT,
 *     converted with Google's official conversion script or downloaded
 *     pre-converted from Kaggle/HuggingFace) and it runs on-device.
 *   - Trade-off: you're tied to MediaPipe's supported model family (Gemma,
 *     Phi-2, Falcon-RW-1B, StableLM) rather than "any GGUF", and the
 *     library itself is a larger dependency than a stripped llama.cpp build.
 *
 * Same system prompt as the original butler persona.
 */
class MediaPipeLlmEngine(private val context: Context) {

    private var llm: LlmInference? = null

    companion object {
        const val SYSTEM_PROMPT =
            "You are Jarvis, a highly intelligent, polite, and witty British butler. " +
            "Keep responses concise, direct, and mildly humorous. Speak naturally " +
            "without using emotional tags or stage directions."
    }

    suspend fun load(modelFile: File, maxTokens: Int = 512) = withContext(Dispatchers.IO) {
        require(modelFile.exists()) { "Model file not found at ${modelFile.absolutePath}" }
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(maxTokens)
            .build()
        llm = LlmInference.createFromOptions(context, options)
    }

    fun isLoaded(): Boolean = llm != null

    suspend fun generate(userPrompt: String): String = withContext(Dispatchers.IO) {
        val engine = llm ?: error("MediaPipeLlmEngine.load() must complete before generate()")
        // MediaPipe's LlmInference doesn't take a separate system-prompt field on this
        // model family, so we fold the persona into the turn itself.
        val fullPrompt = "$SYSTEM_PROMPT\n\nUser: $userPrompt\nJarvis:"
        engine.generateResponse(fullPrompt).trim()
    }

    /** Streaming variant — useful if you want the visualizer to react while tokens arrive. */
    suspend fun generateStreaming(userPrompt: String, onPartial: (String) -> Unit): String =
        suspendCancellableCoroutine { cont ->
            val engine = llm ?: run {
                cont.resume("")
                return@suspendCancellableCoroutine
            }
            val fullPrompt = "$SYSTEM_PROMPT\n\nUser: $userPrompt\nJarvis:"
            val builder = StringBuilder()
            engine.generateResponseAsync(fullPrompt) { partial, done ->
                builder.append(partial)
                onPartial(partial)
                if (done) cont.resume(builder.toString().trim())
            }
        }

    fun unload() {
        llm?.close()
        llm = null
    }
}
