package com.jarvis.assistant.ai

import com.jarvis.assistant.files.FileToolManager

/**
 * Very small keyword-based intent router. Voice commands about files are
 * handled directly and deterministically (no LLM round-trip needed); anything
 * else falls through to the local LLM for a conversational reply.
 *
 * This is intentionally simple rather than a full slot-filling NLU stack —
 * it covers the four file operations called out in the spec and is easy to
 * extend with more regexes/intents later.
 */
class CommandRouter(private val fileTools: FileToolManager) {

    sealed class RouteResult {
        data class SpokenReply(val text: String) : RouteResult()
        data class NeedsLlm(val prompt: String) : RouteResult()
    }

    private val createFolderRegex = Regex("""create (?:a )?folder(?: called| named)? (.+)""", RegexOption.IGNORE_CASE)
    private val searchRegex = Regex("""(?:search|find|look) for (?:files? )?(?:named |called )?(.+)""", RegexOption.IGNORE_CASE)
    private val moveRegex = Regex("""move (.+) to (.+)""", RegexOption.IGNORE_CASE)
    private val deleteRegex = Regex("""delete (.+)""", RegexOption.IGNORE_CASE)
    private val organizeRegex = Regex("""organi[sz]e (?:my )?(.+)""", RegexOption.IGNORE_CASE)

    suspend fun route(command: String): RouteResult {
        val trimmed = command.trim()

        createFolderRegex.find(trimmed)?.let { match ->
            val name = match.groupValues[1].trim()
            val result = fileTools.createFolder(name)
            return RouteResult.SpokenReply(
                if (result.isSuccess) "Done. I've created the folder \"$name\", sir."
                else "I'm afraid I couldn't create that folder — ${result.exceptionOrNull()?.message}."
            )
        }

        searchRegex.find(trimmed)?.let { match ->
            val keyword = match.groupValues[1].trim()
            val hits = fileTools.searchFiles(keyword)
            return RouteResult.SpokenReply(
                if (hits.isEmpty()) "I couldn't find anything matching \"$keyword\"."
                else "I found ${hits.size} item${if (hits.size == 1) "" else "s"} matching \"$keyword\". The first is ${hits.first().name}."
            )
        }

        moveRegex.find(trimmed)?.let { match ->
            val (src, dest) = match.destructured
            val result = fileTools.moveFile(src.trim(), dest.trim())
            return RouteResult.SpokenReply(
                if (result.isSuccess) "Moved, sir."
                else "I couldn't complete that move — ${result.exceptionOrNull()?.message}."
            )
        }

        deleteRegex.find(trimmed)?.let { match ->
            val target = match.groupValues[1].trim()
            val result = fileTools.deleteFile(target)
            return RouteResult.SpokenReply(
                if (result.isSuccess) "Consider it gone."
                else "I couldn't delete that — ${result.exceptionOrNull()?.message}."
            )
        }

        organizeRegex.find(trimmed)?.let { match ->
            val folder = match.groupValues[1].trim().removePrefix("folder").trim()
            val summary = fileTools.organizeByType(folder)
            return RouteResult.SpokenReply(
                if (summary.isEmpty()) "There was nothing to organize in there."
                else "Tidied up — " + summary.entries.joinToString(", ") { "${it.value} ${it.key} files" } + "."
            )
        }

        // No file intent matched — hand off to the LLM for a normal conversational reply.
        return RouteResult.NeedsLlm(trimmed)
    }
}
