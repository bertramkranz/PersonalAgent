package com.personalagent.bertbot.app

import java.io.File

internal object McpServerBootstrap {
    data class DispatcherContextInput(
        val aiRuntimeConfiguration: AiRuntimeConfiguration,
        val macrofactorRuntimeConfiguration: MacrofactorRuntimeConfiguration,
        val googleWorkspaceRuntimeConfiguration: GoogleWorkspaceRuntimeConfiguration,
        val shoppingRuntimeConfiguration: ShoppingRuntimeConfiguration = ShoppingRuntimeConfiguration(),
        val workspaceRoot: File,
        val toolNames: McpToolNames,
        val checkpointRollbackPolicy: CheckpointRollbackPolicyConfiguration? = null,
    )

    data class DispatcherContext(
        val startup: McpStartupState,
        val dispatcher: McpRequestDispatcher,
        val macrofactorToolRouter: MacrofactorToolRouter?,
        val googleWorkspaceToolRouter: GoogleWorkspaceToolRouter?,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun createDispatcherContext(input: DispatcherContextInput): DispatcherContext {
        val startup = createStartupState(input.aiRuntimeConfiguration, input.workspaceRoot)
        val checkpointRollbackPolicy = input.checkpointRollbackPolicy ?: resolveCheckpointRollbackPolicyConfiguration()
        val macrofactorToolRouter =
            if (input.macrofactorRuntimeConfiguration.enabled) {
                MacrofactorToolRouter(input.macrofactorRuntimeConfiguration)
            } else {
                null
            }
        val googleWorkspaceToolRouter =
            if (input.googleWorkspaceRuntimeConfiguration.enabled) {
                GoogleWorkspaceToolRouter(input.googleWorkspaceRuntimeConfiguration)
            } else {
                null
            }
        val shoppingToolRouter =
            if (input.shoppingRuntimeConfiguration.enabled) {
                ShoppingToolRouter(input.shoppingRuntimeConfiguration)
            } else {
                null
            }
        val continuousResearchToolRouter =
            startup.runtime
                ?.researchService()
                ?.let { service -> ContinuousResearchToolRouter(service) }

        val statusProvider =
            McpStatusProviderFactory.create(
                McpStatusProviderInput(
                    startup = startup,
                    workspaceRoot = input.workspaceRoot,
                    aiRuntimeConfiguration = input.aiRuntimeConfiguration,
                    macrofactorRuntimeConfiguration = input.macrofactorRuntimeConfiguration,
                    googleWorkspaceRuntimeConfiguration = input.googleWorkspaceRuntimeConfiguration,
                    macrofactorToolRouter = macrofactorToolRouter,
                    googleWorkspaceToolRouter = googleWorkspaceToolRouter,
                    continuousResearchToolRouter = continuousResearchToolRouter,
                    toolNames = input.toolNames,
                    checkpointRollbackPolicy = checkpointRollbackPolicy,
                ),
            )

        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { prompt, requestCorrelationId ->
                    val runtime = startup.runtime
                    if (runtime == null) {
                        error(startup.errorMessage ?: "BertBot runtime is unavailable.")
                    }
                    runtime.respondTo(
                        userMessage = prompt,
                        emitFallbackMessage = false,
                        traceCorrelationId = requestCorrelationId,
                    )
                },
                workspaceRoot = input.workspaceRoot,
                macrofactorToolRouter = macrofactorToolRouter,
                googleWorkspaceToolRouter = googleWorkspaceToolRouter,
                polymarketToolRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment()),
                continuousResearchToolRouter = continuousResearchToolRouter,
                shoppingToolRouter = shoppingToolRouter,
                ingestionControlPlane = startup.runtime?.ingestionControlPlane(),
                externalChatResponder = startup.runtime?.let { runtime -> { message, dryRun -> runtime.chatFromExternalMessage(message, dryRun) } },
                listCheckpoints = startup.runtime?.let { runtime -> { scopeKey -> runtime.listCheckpoints(scopeKey ?: "global") } },
                latestCheckpoint = startup.runtime?.let { runtime -> { scopeKey -> runtime.latestCheckpoint(scopeKey ?: "global") } },
                checkpointById = startup.runtime?.let { runtime -> { checkpointId, scopeKey -> runtime.checkpointById(checkpointId, scopeKey ?: "global") } },
                rollbackToCheckpoint = startup.runtime?.let { runtime -> { checkpointId, scopeKey -> runtime.rollbackToCheckpoint(checkpointId, scopeKey ?: "global") } },
                checkpointRollbackPolicy = checkpointRollbackPolicy,
                statusProvider = statusProvider,
            )

        return DispatcherContext(
            startup = startup,
            dispatcher = dispatcher,
            macrofactorToolRouter = macrofactorToolRouter,
            googleWorkspaceToolRouter = googleWorkspaceToolRouter,
        )
    }

    private fun createStartupState(
        aiRuntimeConfiguration: AiRuntimeConfiguration,
        workspaceRoot: File,
    ): McpStartupState {
        return try {
            val runtime =
                BertBotRuntimeFactory.create(
                    aiRuntimeConfiguration = aiRuntimeConfiguration,
                    workspaceRoot = workspaceRoot,
                    enablePeriodicResearchScheduler = true,
                )
            if (runtime == null) {
                McpStartupState(runtime = null, errorMessage = "Missing AI provider API key (BERTBOT_AI_API_KEY).")
            } else {
                McpStartupState(runtime = runtime)
            }
        } catch (e: Exception) {
            McpStartupState(runtime = null, errorMessage = e.message ?: "runtime initialization failed")
        }
    }
}
