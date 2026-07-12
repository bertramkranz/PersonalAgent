package com.personalagent.bertbot.app

import java.io.File
import java.time.Instant

internal object McpStatusProviderFactory {
    fun create(input: McpStatusProviderInput): () -> String {
        val macrofactorToolNames =
            input.macrofactorToolRouter
                ?.toolDefinitions()
                ?.mapNotNull { it.get("name")?.asString?.takeIf { name -> name.isNotBlank() } }
                ?: emptyList()
        val researchToolNames =
            input.continuousResearchToolRouter
                ?.toolDefinitions()
                ?.mapNotNull { it.get("name")?.asString?.takeIf { name -> name.isNotBlank() } }
                ?: emptyList()
        val googleWorkspaceToolNames =
            input.googleWorkspaceToolRouter
                ?.toolDefinitions()
                ?.mapNotNull { it.get("name")?.asString?.takeIf { name -> name.isNotBlank() } }
                ?: emptyList()

        return {
            val baseTools =
                mutableListOf(
                    input.toolNames.askBertBot,
                    input.toolNames.bertBotStatus,
                    input.toolNames.workspaceListDir,
                    input.toolNames.workspaceReadFile,
                    input.toolNames.workspaceSearch,
                    input.toolNames.polymarketGamma,
                    input.toolNames.polymarketClob,
                    input.toolNames.polymarketData,
                    input.toolNames.checkpointList,
                    input.toolNames.checkpointLatest,
                    input.toolNames.checkpointGet,
                    input.toolNames.checkpointRollback,
                    input.toolNames.checkpointRollbackLatest,
                    input.toolNames.checkpointPolicy,
                )

            if (input.startup.runtime?.ingestionControlPlane() != null) {
                baseTools += input.toolNames.ingestionSetApproval
                baseTools += input.toolNames.ingestionListApprovedSources
                baseTools += input.toolNames.ingestionIngestManual
                baseTools += input.toolNames.ingestionChatManual
            }

            macrofactorToolNames.forEach { name -> baseTools += name }
            researchToolNames.forEach { name -> baseTools += name }
            googleWorkspaceToolNames.forEach { name -> baseTools += name }

            """
            Connected to bertbot MCP server.
            Active tool surface: ${baseTools.joinToString()}
            Workspace root: ${input.workspaceRoot.absolutePath}
            Runtime ready: ${input.startup.runtime != null}
            Runtime provider: ${input.aiRuntimeConfiguration.provider}
            Runtime model: ${input.aiRuntimeConfiguration.model}
            Runtime error: ${input.startup.errorMessage ?: "none"}
                        Checkpoint rollback policy:
                            environment=${input.checkpointRollbackPolicy.environment}
                            protectedEnvironment=${input.checkpointRollbackPolicy.isProtectedEnvironment}
                            rollbackEnabled=${input.checkpointRollbackPolicy.rollbackEnabled}
                            requireConfirm=${input.checkpointRollbackPolicy.requireConfirm}
                            allowInProtectedEnvironment=${input.checkpointRollbackPolicy.allowInProtectedEnvironment}
            Session check timestamp: ${Instant.now()}
            """.trimIndent()
        }
    }
}

internal data class McpStatusProviderInput(
    val startup: McpStartupState,
    val workspaceRoot: File,
    val aiRuntimeConfiguration: AiRuntimeConfiguration,
    val macrofactorToolRouter: MacrofactorToolRouter?,
    val googleWorkspaceToolRouter: GoogleWorkspaceToolRouter?,
    val continuousResearchToolRouter: ContinuousResearchToolRouter?,
    val toolNames: McpToolNames,
    val checkpointRollbackPolicy: CheckpointRollbackPolicyConfiguration,
)
