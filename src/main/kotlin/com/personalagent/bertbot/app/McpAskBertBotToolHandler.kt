package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import java.io.File

internal class McpAskBertBotToolHandler(
    workspaceRoot: File,
    private val respondToPrompt: (String, String?) -> String?,
    private val statusProvider: () -> String,
    private val backendUnavailableMarkers: List<String>,
    private val evidenceHintKeywords: List<String>,
    private val statusHintKeywords: List<String>,
) {
    private val workspaceRootFile = workspaceRoot.canonicalFile
    private val workspaceInspector = McpAskWorkspaceInspector(workspaceRootFile)

    fun handle(
        params: JsonObject,
        requestCorrelationId: String?,
    ): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val prompt =
            arguments.stringValue("prompt")
                ?: arguments.stringValue("input")
                ?: arguments.stringValue("text")
                ?: arguments.stringValue("query")

        if (prompt.isNullOrBlank()) {
            return true to "Missing prompt input for ask_bertbot."
        }

        if (mcpAskIsStatusProbePrompt(prompt)) {
            return false to mcpAskBuildDeterministicBackendStatus()
        }

        return try {
            val promptWithContext = mcpAskBuildPromptWithWorkspaceEvidence(prompt)
            val rawResponse = respondToPrompt(promptWithContext, requestCorrelationId)
            val response = mcpAskRewriteFalseWorkspaceUnavailable(prompt, rawResponse)
            if (response.isNullOrBlank()) {
                true to "BertBot did not return a response."
            } else {
                false to response
            }
        } catch (e: Exception) {
            true to "BertBot failed: ${e.message ?: "unknown error"}"
        }
    }

    private fun mcpAskIsStatusProbePrompt(prompt: String): Boolean {
        val normalizedPrompt = prompt.lowercase()
        if (statusHintKeywords.any { keyword -> normalizedPrompt.contains(keyword) }) {
            return true
        }

        val compactPrompt = normalizedPrompt.replace(Regex("\\s+"), " ").trim()
        return compactPrompt == "bert_bot status" || compactPrompt == "bertbot status"
    }

    private fun mcpAskBuildDeterministicBackendStatus(): String {
        val rootAccessible = workspaceRootFile.exists() && workspaceRootFile.isDirectory
        val rootEntries = workspaceRootFile.listFiles()?.size ?: 0
        return listOf(
            statusProvider(),
            "Workspace root accessible: $rootAccessible",
            "Workspace entry count: $rootEntries",
            "Backend routing: available",
        ).joinToString(separator = "\n")
    }

    private fun mcpAskBuildPromptWithWorkspaceEvidence(prompt: String): String {
        if (!mcpAskRequiresWorkspaceEvidence(prompt)) {
            return prompt
        }

        val evidence = mcpAskBuildWorkspaceEvidenceSummary()
        return listOf(
            prompt,
            "",
            "Backend-verified workspace evidence (do not claim lack of repository visibility unless this section explicitly says unavailable):",
            evidence,
        ).joinToString(separator = "\n")
    }

    private fun mcpAskRewriteFalseWorkspaceUnavailable(
        originalPrompt: String,
        rawResponse: String?,
    ): String? {
        if (rawResponse.isNullOrBlank()) {
            return rawResponse
        }

        if (!mcpAskRequiresWorkspaceEvidence(originalPrompt)) {
            return rawResponse
        }

        val normalizedResponse = rawResponse.lowercase()
        val hasUnavailableMarker = backendUnavailableMarkers.any { marker -> normalizedResponse.contains(marker) }
        if (!hasUnavailableMarker) {
            return rawResponse
        }

        val hasRepoEvidence = mcpAskHasWorkspaceEvidenceForRepoPaths()
        if (!hasRepoEvidence) {
            return rawResponse
        }

        val evidence = mcpAskBuildWorkspaceEvidenceSummary()
        return listOf(
            "Backend workspace tools are available in this session, and repository evidence was verified.",
            "",
            "Verified evidence:",
            evidence,
            "",
            "The prior backend response claimed workspace unavailability, but that claim contradicts verified local evidence.",
            "Proceed with repository-backed findings using the evidence above.",
        ).joinToString(separator = "\n")
    }

    private fun mcpAskRequiresWorkspaceEvidence(prompt: String): Boolean {
        val normalizedPrompt = prompt.lowercase()
        return evidenceHintKeywords.any { keyword -> normalizedPrompt.contains(keyword) }
    }

    private fun mcpAskBuildWorkspaceEvidenceSummary(): String {
        val topLevelEntries = workspaceInspector.listTopLevelEntries()
        val mainPackages = workspaceInspector.listImmediateChildren("src/main/kotlin/com/personalagent/bertbot")
        val testPackages = workspaceInspector.listImmediateChildren("src/test/kotlin/com/personalagent/bertbot")

        return listOf(
            "- workspace root: ${workspaceRootFile.absolutePath}",
            "- top-level entries: $topLevelEntries",
            "- src/main/kotlin/com/personalagent/bertbot children: $mainPackages",
            "- src/test/kotlin/com/personalagent/bertbot children: $testPackages",
        ).joinToString(separator = "\n")
    }

    private fun mcpAskHasWorkspaceEvidenceForRepoPaths(): Boolean {
        val mainDir = workspaceInspector.resolveWorkspacePath("src/main/kotlin/com/personalagent/bertbot")
        val testDir = workspaceInspector.resolveWorkspacePath("src/test/kotlin/com/personalagent/bertbot")
        val hasMain = mainDir != null && mainDir.exists() && mainDir.isDirectory
        val hasTest = testDir != null && testDir.exists() && testDir.isDirectory
        return hasMain || hasTest
    }
}

private class McpAskWorkspaceInspector(
    private val workspaceRootFile: File,
) {
    fun listTopLevelEntries(): String =
        workspaceRootFile
            .listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.joinToString(separator = ", ") { file ->
                if (file.isDirectory) {
                    "${file.name}/"
                } else {
                    file.name
                }
            }
            ?: "(unavailable)"

    fun listImmediateChildren(relativePath: String): String {
        val targetDir = resolveWorkspacePath(relativePath)
        if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
            return "(unavailable)"
        }

        val children =
            targetDir
                .listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.joinToString(separator = ", ") { file ->
                    if (file.isDirectory) {
                        "${file.name}/"
                    } else {
                        file.name
                    }
                }

        return if (children.isNullOrBlank()) "(empty)" else children
    }

    fun resolveWorkspacePath(pathValue: String): File? {
        val candidate =
            if (File(pathValue).isAbsolute) {
                File(pathValue)
            } else {
                File(workspaceRootFile, pathValue)
            }

        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (canonicalCandidate.mcpAskIsWithin(workspaceRootFile)) canonicalCandidate else null
    }
}

private fun File.mcpAskIsWithin(root: File): Boolean {
    val targetPath = canonicalFile.toPath().normalize()
    val rootPath = root.canonicalFile.toPath().normalize()
    return targetPath == rootPath || targetPath.startsWith(rootPath)
}
