package com.personalagent.bertbot.app

import java.io.File

internal class ResearchWorkspaceInspector(
    private val workspaceRoot: File,
) {
    fun containsFileName(token: String): Boolean {
        val normalizedToken = token.lowercase()
        return walkWorkspaceFiles().any { file -> file.name.lowercase().contains(normalizedToken) }
    }

    fun containsToken(token: String): Boolean {
        val normalizedToken = token.lowercase()
        return walkWorkspaceFiles().any { file ->
            val containsTokenInContent =
                runCatching { file.useLines { lines -> lines.any { line -> line.contains(normalizedToken, ignoreCase = true) } } }
                    .getOrDefault(false)
            file.name.contains(normalizedToken, ignoreCase = true) || containsTokenInContent
        }
    }

    fun snapshot(maxFiles: Int = 40): String {
        val files =
            walkWorkspaceFiles()
                .map { file ->
                    runCatching {
                        workspaceRoot.toPath().relativize(file.toPath()).toString().replace("\\", "/")
                    }.getOrDefault(file.name)
                }.take(maxFiles)
                .toList()

        return if (files.isEmpty()) {
            "(no files discovered)"
        } else {
            files.joinToString(separator = "\n") { path -> "- $path" }
        }
    }

    private fun walkWorkspaceFiles() =
        workspaceRoot
            .walkTopDown()
            .onEnter { directory ->
                directory.name != ".git" && directory.name != "build" && directory.name != ".gradle"
            }.filter { file -> file.isFile }
}
