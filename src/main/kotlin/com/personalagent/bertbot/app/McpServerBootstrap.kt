package com.personalagent.bertbot.app

import java.io.File

internal object McpServerBootstrap {
    data class DispatcherContextInput(
        val aiRuntimeConfiguration: AiRuntimeConfiguration,
        val macrofactorRuntimeConfiguration: MacrofactorRuntimeConfiguration,
        val googleWorkspaceRuntimeConfiguration: GoogleWorkspaceRuntimeConfiguration,
        val workspaceRoot: File,
        val toolNames: McpToolNames,
    )

    data class DispatcherContext(
        val startup: McpStartupState,
        val dispatcher: McpRequestDispatcher,
    )

    fun createDispatcherContext(input: DispatcherContextInput): DispatcherContext {
        val startup = createStartupState(input.aiRuntimeConfiguration, input.workspaceRoot)
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
                    macrofactorToolRouter = macrofactorToolRouter,
                    googleWorkspaceToolRouter = googleWorkspaceToolRouter,
                    continuousResearchToolRouter = continuousResearchToolRouter,
                    toolNames = input.toolNames,
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
                ingestionControlPlane = startup.runtime?.ingestionControlPlane(),
                externalChatResponder = startup.runtime?.let { runtime -> { message, dryRun -> runtime.chatFromExternalMessage(message, dryRun) } },
                statusProvider = statusProvider,
            )

        return DispatcherContext(startup = startup, dispatcher = dispatcher)
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
