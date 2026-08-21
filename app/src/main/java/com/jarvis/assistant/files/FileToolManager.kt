package com.jarvis.assistant.files

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native file-system tools exposed to Jarvis's command parser. Requires
 * MANAGE_EXTERNAL_STORAGE (granted via Settings by MainActivity) on API 30+.
 * All calls are suspend + IO-dispatched since directory walks can be slow
 * on large storage.
 */
class FileToolManager(private val context: Context) {

    private val root: File get() = Environment.getExternalStorageDirectory()

    fun hasFullAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true

    /** Recursively search for files/folders whose name contains [keyword] (case-insensitive). */
    suspend fun searchFiles(keyword: String, maxResults: Int = 20): List<File> = withContext(Dispatchers.IO) {
        val results = mutableListOf<File>()
        walk(root) { file ->
            if (results.size >= maxResults) return@walk false
            if (file.name.contains(keyword, ignoreCase = true)) results.add(file)
            true
        }
        results
    }

    /** List immediate contents of a directory relative to external storage root, or root if blank. */
    suspend fun listFiles(relativePath: String = ""): List<File> = withContext(Dispatchers.IO) {
        val dir = if (relativePath.isBlank()) root else File(root, relativePath)
        dir.listFiles()?.toList() ?: emptyList()
    }

    suspend fun createFolder(name: String, parentRelativePath: String = ""): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = if (parentRelativePath.isBlank()) root else File(root, parentRelativePath)
            val target = File(parent, name)
            if (!target.exists() && !target.mkdirs()) {
                error("Failed to create folder ${target.absolutePath}")
            }
            target
        }
    }

    suspend fun moveFile(sourceRelativePath: String, destRelativePath: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(root, sourceRelativePath)
                val dest = File(root, destRelativePath)
                if (!src.exists()) error("Source not found: $sourceRelativePath")
                dest.parentFile?.mkdirs()
                if (!src.renameTo(dest)) {
                    // Fallback to copy+delete across filesystem boundaries.
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                }
                dest
            }
        }

    suspend fun deleteFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(root, relativePath)
            if (!target.exists()) error("Not found: $relativePath")
            val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (!ok) error("Failed to delete $relativePath")
        }
    }

    /**
     * Simple "organize" helper: groups files in [relativePath] into subfolders
     * by extension (e.g. Downloads/pdf, Downloads/jpg). Returns a count summary.
     */
    suspend fun organizeByType(relativePath: String): Map<String, Int> = withContext(Dispatchers.IO) {
        val dir = if (relativePath.isBlank()) root else File(root, relativePath)
        val moved = mutableMapOf<String, Int>()
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val ext = file.extension.ifBlank { "misc" }.lowercase()
            val destDir = File(dir, ext).apply { mkdirs() }
            val dest = File(destDir, file.name)
            if (file.renameTo(dest)) {
                moved[ext] = (moved[ext] ?: 0) + 1
            }
        }
        moved
    }

    private fun walk(dir: File, visit: (File) -> Boolean) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (!visit(child)) return
            if (child.isDirectory) walk(child, visit)
        }
    }
}
