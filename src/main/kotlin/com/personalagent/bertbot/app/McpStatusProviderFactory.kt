package com.personalagent.bertbot.app

import java.io.File
import java.time.Instant

internal object McpStatusProviderFactory {
    @Suppress("LongMethod")
    fun create(input: McpStatusProviderInput): () -> String {
        return {
            val macrofactorStatus =
                summarizeMacrofactorAvailability(input.macrofactorRuntimeConfiguration, input.macrofactorToolRouter)
            val googleWorkspaceStatus =
                summarizeGoogleWorkspaceAvailability(input.googleWorkspaceRuntimeConfiguration, input.googleWorkspaceToolRouter)
            val macrofactorToolNames =
                extractToolNames(input.macrofactorToolRouter)
            val researchToolNames =
                extractToolNames(input.continuousResearchToolRouter)
            val sessionHistoryToolNames =
                extractToolNames(input.sessionHistoryToolRouter)
            val learningReviewToolNames =
                extractToolNames(input.learningReviewToolRouter)
            val proceduralSkillToolNames =
                extractToolNames(input.proceduralSkillToolRouter)
            val scheduledJobToolNames =
                extractToolNames(input.scheduledJobToolRouter)
            val googleWorkspaceToolNames =
                extractToolNames(input.googleWorkspaceToolRouter)

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
            sessionHistoryToolNames.forEach { name -> baseTools += name }
            learningReviewToolNames.forEach { name -> baseTools += name }
            proceduralSkillToolNames.forEach { name -> baseTools += name }
            scheduledJobToolNames.forEach { name -> baseTools += name }
            googleWorkspaceToolNames.forEach { name -> baseTools += name }

            """
            Connected to bertbot MCP server.
            Active tool surface: ${baseTools.joinToString()}
            Workspace root: ${input.workspaceRoot.absolutePath}
            Runtime ready: ${input.startup.runtime != null}
            Runtime provider: ${input.aiRuntimeConfiguration.provider}
            Runtime model: ${input.aiRuntimeConfiguration.model}
            Runtime error: ${input.startup.errorMessage ?: "none"}
            MacroFactor MCP: $macrofactorStatus
            Google Workspace MCP: $googleWorkspaceStatus
                        Learning review policy:
                            enabled=${input.learningReviewConfiguration.enabled}
                            memoryWriteApprovalRequired=${input.learningReviewConfiguration.memoryWriteApprovalRequired}
                            skillWriteApprovalRequired=${input.learningReviewConfiguration.skillWriteApprovalRequired}
                            approvalQueueEnabled=${input.learningReviewToolRouter != null}
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

private fun extractToolNames(router: ToolRouter?): List<String> =
    router
        ?.toolDefinitions()
        ?.mapNotNull { it.get("name")?.asString?.takeIf { name -> name.isNotBlank() } }
        ?: emptyList()

internal data class McpStatusProviderInput(
    val startup: McpStartupState,
    val workspaceRoot: File,
    val aiRuntimeConfiguration: AiRuntimeConfiguration,
    val macrofactorRuntimeConfiguration: MacrofactorRuntimeConfiguration,
    val googleWorkspaceRuntimeConfiguration: GoogleWorkspaceRuntimeConfiguration,
    val macrofactorToolRouter: MacrofactorToolRouter?,
    val googleWorkspaceToolRouter: GoogleWorkspaceToolRouter?,
    val continuousResearchToolRouter: ContinuousResearchToolRouter?,
    val sessionHistoryToolRouter: SessionHistoryToolRouter?,
    val learningReviewToolRouter: LearningReviewToolRouter?,
    val proceduralSkillToolRouter: ProceduralSkillToolRouter?,
    val scheduledJobToolRouter: ScheduledJobToolRouter?,
    val learningReviewConfiguration: LearningReviewRuntimeConfiguration,
    val toolNames: McpToolNames,
    val checkpointRollbackPolicy: CheckpointRollbackPolicyConfiguration,
)
