package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import java.io.File

internal class McpWorkspaceToolHandler(
    workspaceRoot: File,
) {
    private val workspaceRootFile = workspaceRoot.canonicalFile
    private val inspector = McpWorkspaceFileInspector(workspaceRootFile)

    fun listDir(params: JsonObject): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val pathValue = arguments.stringValue("path") ?: "."
        val directory = inspector.resolveWorkspacePath(pathValue)
        if (directory == null) {
            return true to "Path is outside workspace root."
        }

        if (!directory.exists()) {
            return true to "Path does not exist: $pathValue"
        }
        if (!directory.isDirectory) {
            return true to "Path is not a directory: $pathValue"
        }

        val entries =
            directory
                .listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.joinToString(separator = "\n") { file ->
                    val suffix = if (file.isDirectory) "/" else ""
                    "${inspector.toWorkspaceRelativePath(file)}$suffix"
                }
                ?: ""

        val body = if (entries.isBlank()) "(empty directory)" else entries
        return false to body
    }

    fun readFile(params: JsonObject): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val pathValue = arguments.stringValue("path") ?: arguments.stringValue("filePath")
        if (pathValue.isNullOrBlank()) {
            return true to "Missing required field: path"
        }

        val file = inspector.resolveWorkspacePath(pathValue)
        if (file == null) {
            return true to "Path is outside workspace root."
        }

        if (!file.exists()) {
            return true to "File does not exist: $pathValue"
        }
        if (!file.isFile) {
            return true to "Path is not a file: $pathValue"
        }

        val maxChars = 50_000
        val builder = StringBuilder()
        file.reader().use { reader ->
            val buffer = CharArray(4096)
            while (builder.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - builder.length))
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }
        }
        val truncated = builder.toString()
        val output =
            if (file.length() > maxChars) {
                "$truncated\n\n[truncated: showing first $maxChars characters]"
            } else {
                truncated
            }
        return false to output
    }

    fun search(params: JsonObject): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val query = arguments.stringValue("query")
        if (query.isNullOrBlank()) {
            return true to "Missing required field: query"
        }

        val maxResults = (arguments.intValue("maxResults") ?: 20).coerceIn(1, 200)
        val files =
            workspaceRootFile
                .walkTopDown()
                .onEnter { dir -> dir.name != ".git" && dir.name != "build" && dir.name != ".gradle" }
                .filter { it.isFile }

        val matches = mutableListOf<String>()
        files.forEach { file ->
            if (matches.size >= maxResults) {
                return@forEach
            }

            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.withIndex()
                        .firstOrNull { (_, line) -> line.contains(query, ignoreCase = true) }
                        ?.let { (index, line) ->
                            val lineNumber = index + 1
                            val snippet = line.trim().take(200)
                            matches.add("${inspector.toWorkspaceRelativePath(file)}:$lineNumber: $snippet")
                        }
                }
            }
        }

        val body = if (matches.isEmpty()) "No matches found." else matches.joinToString(separator = "\n")
        return false to body
    }
}

private class McpWorkspaceFileInspector(
    private val workspaceRootFile: File,
) {
    fun resolveWorkspacePath(pathValue: String): File? {
        val candidate =
            if (File(pathValue).isAbsolute) {
                File(pathValue)
            } else {
                File(workspaceRootFile, pathValue)
            }

        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (canonicalCandidate.mcpWorkspaceIsWithin(workspaceRootFile)) canonicalCandidate else null
    }

    fun toWorkspaceRelativePath(file: File): String {
        val workspacePath = workspaceRootFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return workspacePath.relativize(filePath).toString().replace("\\", "/")
    }
}

private fun File.mcpWorkspaceIsWithin(root: File): Boolean {
    val targetPath = canonicalFile.toPath().normalize()
    val rootPath = root.canonicalFile.toPath().normalize()
    return targetPath == rootPath || targetPath.startsWith(rootPath)
}

private fun JsonObject.intValue(name: String): Int? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        return null
    }
    return runCatching { element.asInt }.getOrNull()
}
