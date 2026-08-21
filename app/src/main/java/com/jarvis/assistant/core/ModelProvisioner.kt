package com.jarvis.assistant.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Handles one-time setup on first launch: unpacks the Vosk model (bundled as
 * a zip in assets/) and copies the LLM model file into app-private storage,
 * so the user never has to `adb push` anything manually. Put the actual
 * model files in `app/src/main/assets/` before building — see README.md.
 *
 * Safe to call on every service start; each step short-circuits if its
 * target already exists.
 */
class ModelProvisioner(private val context: Context) {

    sealed class Progress {
        data class Status(val message: String) : Progress()
        data class Done(val ready: Boolean) : Progress()
        data class Error(val message: String) : Progress()
    }

    private val modelsDir = File(context.filesDir, "models")

    val voskModelDir = File(modelsDir, "vosk-model-small-en-us")
    val llmModelFile = File(modelsDir, LLM_MODEL_FILENAME)

    /** Returns true once both required assets are present on disk (models already provisioned). */
    fun isFullyProvisioned(): Boolean =
        voskModelDir.exists() && voskModelDir.listFiles()?.isNotEmpty() == true && llmModelFile.exists()

    suspend fun provision(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()

        // 1. Vosk model — bundled as assets/vosk-model-small-en-us.zip
        if (!voskModelDir.exists() || voskModelDir.listFiles()?.isEmpty() != false) {
            onProgress(Progress.Status("Unpacking speech recognition model…"))
            runCatching {
                unzipAsset(VOSK_ASSET_NAME, modelsDir)
            }.onFailure {
                onProgress(Progress.Error("Missing ${VOSK_ASSET_NAME} in assets/ — see README.md"))
                return@withContext
            }
        }

        // 2. LLM model — bundled as assets/<LLM_MODEL_FILENAME> (or split parts, see copyAssetSplit)
        if (!llmModelFile.exists()) {
            onProgress(Progress.Status("Copying language model (this can take a minute)…"))
            runCatching {
                copyAsset(LLM_MODEL_FILENAME, llmModelFile)
            }.onFailure {
                onProgress(Progress.Error("Missing $LLM_MODEL_FILENAME in assets/ — see README.md"))
                return@withContext
            }
        }

        onProgress(Progress.Done(isFullyProvisioned()))
    }

    private fun unzipAsset(assetName: String, destDir: File) {
        context.assets.open(assetName).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun copyAsset(assetName: String, destFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        }
    }

    companion object {
        private const val VOSK_ASSET_NAME = "vosk-model-small-en-us.zip"
        // MediaPipe LLM Inference expects a .task bundle (e.g. Gemma 3 1B IT, int4).
        const val LLM_MODEL_FILENAME = "gemma3-1b-it-int4.task"
    }
}
