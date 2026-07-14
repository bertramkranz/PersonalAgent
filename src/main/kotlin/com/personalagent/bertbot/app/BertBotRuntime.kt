@file:Suppress("TooManyFunctions")

package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.personalagent.bertbot.agents.SelfCorrectingSkill
import com.personalagent.bertbot.agents.SelfCorrectingSkillRequest
import com.personalagent.bertbot.agents.ToolCallingSkill
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotRollbackService
import com.personalagent.bertbot.graph.runtime.MaxTurnsExceededException
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventStore
import com.personalagent.bertbot.graph.runtime.StateReplayService
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import com.personalagent.bertbot.ingestion.connectors.BertBotExternalConnectors
import com.personalagent.bertbot.ingestion.connectors.ExternalChatPayloadDispatcher
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.MemorySummarizationWorker
import com.personalagent.bertbot.memory.UserProfileStore

@Suppress("LongParameterList")
internal class BertBotRuntime(
    val config: BertBotAgentConfig,
    val aiRuntimeConfiguration: AiRuntimeConfiguration,
    private val stateStore: com.personalagent.bertbot.graph.runtime.BertBotStateStore,
    private val graph: BertBotGraphRunner,
    private val assistantResponseSkill: SelfCorrectingSkill<AssistantResponseEnvelope>,
    private val memoryRuntime: BertBotMemoryRuntime,
    private val ingestionRuntime: BertBotIngestionRuntime? = null,
    private val researchRuntime: BertBotResearchRuntime? = null,
    private val toolCallingSkill: ToolCallingSkill? = null,
    private val checkpointStore: BertBotCheckpointStore? = null,
    private val rollbackService: BertBotRollbackService? = null,
    private val stateEventStore: StateEventStore? = null,
    private val stateReplayService: StateReplayService? = null,
    private val koogMemory: KoogMemoryIntegration = KoogMemoryIntegration(),
    private val runtimeCapabilitySnapshot: RuntimeCapabilitySnapshot = RuntimeCapabilitySnapshot(),
    private val runtimeCapabilitySnapshotProvider: (() -> RuntimeCapabilitySnapshot)? = null,
    private val telemetry: RuntimeTelemetry = NoOpRuntimeTelemetry,
) : AutoCloseable {
    private val interactionGraphWriter: InteractionGraphWriter = InteractionGraphWriter()
    private val requestContextBuilder = BertBotRequestContextBuilder(config, memoryRuntime)
    private val externalChatHandler =
        BertBotExternalChatHandler(
            controlPlane = ingestionRuntime?.controlPlane,
            stateStore = stateStore,
            stateEventStore = stateEventStore,
            withPersistenceScope = { scopeKey, action -> withPersistenceScope(scopeKey, action) },
            respondInScope = { scopeKey, userMessage, traceCorrelationId ->
                respondTo(
                    userMessage = userMessage,
                    emitFallbackMessage = false,
                    traceCorrelationId = traceCorrelationId,
                    persistenceScopeKey = scopeKey,
                )
            },
        )
    private var connectorRuntime: BertBotConnectorRuntime = BertBotConnectorRuntime()

    @Suppress("LongMethod")
    fun respondTo(
        userMessage: String,
        emitFallbackMessage: Boolean = true,
        traceCorrelationId: String? = null,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): String? {
        return withPersistenceScope(persistenceScopeKey) {
            val requestSpan =
                telemetry.startSpan(
                    name = "bertbot.respond",
                    attributes =
                        mapOf(
                            "bertbot.scope" to persistenceScopeKey,
                            "bertbot.provider" to aiRuntimeConfiguration.provider,
                            "bertbot.model" to aiRuntimeConfiguration.model,
                        ),
                )
            var telemetryError: Throwable? = null
            try {
                if (isLikelyPromptInjection(userMessage)) {
                    return@withPersistenceScope promptInjectionRefusalMessage()
                }

                val effectiveRuntimeCapabilitySnapshot =
                    runCatching { runtimeCapabilitySnapshotProvider?.invoke() }
                        .getOrNull()
                        ?: runtimeCapabilitySnapshot

                buildCapabilityStatusResponse(config, userMessage, effectiveRuntimeCapabilitySnapshot)?.let { capabilityStatus ->
                    return@withPersistenceScope capabilityStatus
                }
                buildGoogleWorkspaceUnavailableResponse(userMessage, effectiveRuntimeCapabilitySnapshot)?.let { unavailableResponse ->
                    return@withPersistenceScope unavailableResponse
                }

                val requestContext = requestContextBuilder.build(userMessage, traceCorrelationId)

                val state =
                    try {
                        graph.run(
                            initialState = requestContext.initialState,
                            checkpointScopeKey = normalizeScopeKey(persistenceScopeKey),
                        )
                    } catch (e: MaxTurnsExceededException) {
                        if (emitFallbackMessage) {
                            println("Assistant: ${e.fallbackMessage}")
                            println("")
                        }
                        return@withPersistenceScope null
                    }

                val koogPromptContext = koogMemory.buildPromptContext(persistenceScopeKey, userMessage)
                val systemPrompt =
                    buildSystemPrompt(config, state, effectiveRuntimeCapabilitySnapshot).let { base ->
                        if (koogPromptContext.isBlank()) {
                            base
                        } else {
                            "$base\n\nKoog memory context:\n$koogPromptContext"
                        }
                    }
                val tracingContext = TracingContext(traceId = state.traceId ?: requestContext.requestTraceId)
                val response =
                    if (isNameRecallQuestion(userMessage) && !requestContext.knownProfile.displayName.isNullOrBlank()) {
                        TraceLogger.info(tracingContext, "profile_lookup", "resolved_name=true")
                        "Your name is ${requestContext.knownProfile.displayName}."
                    } else if (toolCallingSkill != null) {
                        toolCallingSkill.invoke(systemPrompt, userMessage, tracingContext)
                    } else {
                        assistantResponseSkill
                            .invoke(
                                input =
                                    SelfCorrectingSkillRequest(
                                        systemPrompt = systemPrompt,
                                        userPrompt = userMessage,
                                    ),
                                tracingContext = tracingContext,
                            ).response
                    }

                runCatching {
                    interactionGraphWriter.write(
                        traceId = tracingContext.traceId,
                        state = state,
                        events = TraceLogger.snapshot(tracingContext.traceId),
                    )
                }.onFailure { e ->
                    TraceLogger.warn(tracingContext, "diagram-write-failed", "InteractionGraphWriter failed: ${e.message}")
                }

                memoryRuntime.episodicMemory.append("ASSISTANT: $response")
                runCatching {
                    koogMemory.recordTurn(
                        scopeKey = persistenceScopeKey,
                        userMessage = userMessage,
                        assistantResponse = response,
                        traceId = tracingContext.traceId,
                    )
                }
                memoryRuntime.memoryWorker.scheduleIfNeeded()
                runCatching {
                    researchRuntime?.service?.submitEventAsync(reason = "respond_to")
                }
                response
            } catch (e: Throwable) {
                telemetryError = e
                throw e
            } finally {
                telemetry.endSpan(requestSpan, telemetryError)
            }
        }
    }

    fun ingestExternalMessages(
        messages: List<NormalizedIngestionMessage>,
        dryRun: Boolean = false,
    ): List<IngestionOutcome> {
        val runtime = ingestionRuntime ?: return emptyList()
        return runtime.controlPlane.ingestManual(messages, dryRun)
    }

    fun chatFromExternalMessage(
        message: NormalizedIngestionMessage,
        dryRun: Boolean = false,
    ): ExternalChatOutcome = externalChatHandler.chatFromExternalMessage(message, dryRun)

    fun ingestionControlPlane(): IngestionControlPlane? = ingestionRuntime?.controlPlane

    fun researchService(): ContinuousImprovementResearchService? = researchRuntime?.service

    fun rollbackToCheckpoint(
        checkpointId: String,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): BertBotState {
        val service = requireNotNull(rollbackService) { "Rollback service is not configured." }
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return service.rollbackToCheckpoint(normalizedScopeKey, checkpointId)
    }

    fun rollbackToLatest(persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY): BertBotState {
        val service = requireNotNull(rollbackService) { "Rollback service is not configured." }
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return service.rollbackToLatest(normalizedScopeKey)
    }

    fun listCheckpoints(persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY): List<BertBotCheckpoint> {
        val store = checkpointStore ?: return emptyList()
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.list(normalizedScopeKey)
    }

    fun latestCheckpoint(persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY): BertBotCheckpoint? {
        val store = checkpointStore ?: return null
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.loadLatest(normalizedScopeKey)
    }

    fun checkpointById(
        checkpointId: String,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): BertBotCheckpoint? {
        val store = checkpointStore ?: return null
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.loadById(normalizedScopeKey, checkpointId)
    }

    fun listStateEvents(persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY): List<com.personalagent.bertbot.graph.runtime.StateEvent> {
        val store = stateEventStore ?: return emptyList()
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.list(normalizedScopeKey)
    }

    fun replayStateToCheckpoint(
        checkpointId: String,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): BertBotState {
        val replayService = requireNotNull(stateReplayService) { "State replay service is not configured." }
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return replayService.replayEventsToCheckpoint(normalizedScopeKey, checkpointId)
    }

    fun externalChatResponder(): (NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome =
        { message, dryRun -> chatFromExternalMessage(message, dryRun) }

    internal fun attachConnectorRuntime(runtime: BertBotConnectorRuntime) {
        connectorRuntime = runtime
    }

    fun connectors(): BertBotConnectorRuntime = connectorRuntime

    fun externalPayloadDispatcher(): ExternalChatPayloadDispatcher =
        ExternalChatPayloadDispatcher(
            connectors =
                BertBotExternalConnectors(
                    telegram = connectorRuntime.telegram,
                    slack = connectorRuntime.slack,
                    whatsapp = connectorRuntime.whatsapp,
                    discord = connectorRuntime.discord,
                ),
        )

    override fun close() {
        memoryRuntime.memoryWorker.close()
        ingestionRuntime?.scheduler?.close()
        researchRuntime?.scheduler?.close()
        researchRuntime?.service?.close()
        telemetry.close()
    }

    private fun <T> withPersistenceScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val normalizedScopeKey = normalizeScopeKey(scopeKey)
        return stateStore.withScope(normalizedScopeKey) {
            memoryRuntime.episodicMemory.withScope(normalizedScopeKey) {
                memoryRuntime.semanticMemory.withScope(normalizedScopeKey) {
                    memoryRuntime.userProfileStore.withScope(normalizedScopeKey, action)
                }
            }
        }
    }

    private fun normalizeScopeKey(scopeKey: String): String {
        val normalized = scopeKey.trim().ifBlank { DEFAULT_PERSISTENCE_SCOPE_KEY }
        return normalized.replace("|", "_")
    }

    private companion object {
        private const val DEFAULT_PERSISTENCE_SCOPE_KEY = "global"
    }
}

internal data class BertBotMemoryRuntime(
    val episodicMemory: EpisodicMemory,
    val semanticMemory: com.personalagent.bertbot.memory.SemanticMemory = com.personalagent.bertbot.memory.SemanticMemory(),
    val memoryAssembler: DualMemoryContextAssembler,
    val memoryWorker: MemorySummarizationWorker,
    val userProfileStore: UserProfileStore,
)

internal data class BertBotIngestionRuntime(
    val controlPlane: IngestionControlPlane,
    val scheduler: AutoCloseable? = null,
)

internal data class BertBotResearchRuntime(
    val service: ContinuousImprovementResearchService,
    val scheduler: AutoCloseable? = null,
)

internal object BertBotRuntimeFactory {
    @Suppress("LongMethod")
    fun create(
        config: BertBotAgentConfig = BertBotAgentConfig(),
        aiRuntimeConfiguration: AiRuntimeConfiguration = resolveAiRuntimeConfiguration(),
        workspaceRoot: java.io.File = resolveWorkspaceRoot(),
        enablePeriodicResearchScheduler: Boolean = false,
        googleWorkspaceRouter: GoogleWorkspaceToolRouter? = null,
    ): BertBotRuntime? {
        val runtimeConfig =
            applyResearchRuntimeOverrides(
                config = config,
                environment = System.getenv(),
                dotEnvValues = loadDotEnvValues(),
            )
        val normalizedProvider = aiRuntimeConfiguration.provider.lowercase()

        val persistenceConfiguration = resolvePersistenceRuntimeConfiguration()
        val stateStore = BertBotRuntimeDependenciesFactory.createStateStore(persistenceConfiguration)
        val checkpointStore = BertBotRuntimeDependenciesFactory.createCheckpointStore(persistenceConfiguration)
        val stateEventStore = BertBotRuntimeDependenciesFactory.createStateEventStore(persistenceConfiguration)
        val rollbackService = BertBotRuntimeDependenciesFactory.createRollbackService(stateStore, checkpointStore, stateEventStore)
        val stateReplayService = BertBotRuntimeDependenciesFactory.createStateReplayService(checkpointStore, stateEventStore)
        val graph =
            BertBotApplication.createGraph(
                stateStore = stateStore,
                config = runtimeConfig,
                checkpointStore = checkpointStore,
                enableAutomaticCheckpointing = persistenceConfiguration.checkpointAutoSaveEnabled,
                eventSourcingConfiguration =
                    BertBotGraphRunner.EventSourcingConfiguration(
                        enabled = persistenceConfiguration.eventSourcingEnabled,
                        store = stateEventStore,
                    ),
            )
        val llmGateway =
            when (normalizedProvider) {
                "openai" -> {
                    val apiKey = aiRuntimeConfiguration.apiKey ?: return null
                    createOpenAiLlmGateway(apiKey, aiRuntimeConfiguration.model)
                }
                "ollama" ->
                    createOllamaLlmGateway(
                        baseUrl = aiRuntimeConfiguration.ollamaBaseUrl,
                        modelName = aiRuntimeConfiguration.model,
                        timeoutSeconds = aiRuntimeConfiguration.ollamaTimeoutSeconds,
                    )
                else ->
                    throw IllegalArgumentException(
                        "Unsupported AI provider '${aiRuntimeConfiguration.provider}'. Supported providers: openai, ollama.",
                    )
            }
        val memoryRuntime = BertBotRuntimeDependenciesFactory.createMemoryRuntime(runtimeConfig, llmGateway, persistenceConfiguration)
        val ingestionRuntime =
            BertBotRuntimeDependenciesFactory.createIngestionRuntime(
                runtimeConfig,
                memoryRuntime,
                persistenceConfiguration,
            )
        val researchRuntime =
            BertBotRuntimeDependenciesFactory.createResearchRuntime(
                config = runtimeConfig,
                persistenceConfiguration = persistenceConfiguration,
                workspaceRoot = workspaceRoot,
                enablePeriodicScheduler = enablePeriodicResearchScheduler,
                llmGateway = llmGateway,
            )
        val macrofactorToolRouter = createMacrofactorToolRouterOrNull(resolveMacrofactorRuntimeConfiguration())
        val polymarketToolRouter = createPolymarketToolRouterOrNull(runtimeConfig)
        val googleWorkspaceToolDefinitions = googleWorkspaceRouter?.toolDefinitions().orEmpty()
        val toolCallingSkill =
            buildToolCallingSkillOrNull(
                googleWorkspaceRouter = googleWorkspaceRouter,
                polymarketToolRouter = polymarketToolRouter,
                macrofactorToolRouter = macrofactorToolRouter,
                llmGateway = llmGateway,
                config = runtimeConfig,
            )
        val runtimeCapabilitySnapshot =
            RuntimeCapabilitySnapshot(
                googleWorkspaceConfigured = googleWorkspaceRouter != null,
                googleWorkspaceToolAccessAvailable = googleWorkspaceToolDefinitions.isNotEmpty(),
            )
        val runtimeCapabilitySnapshotProvider =
            googleWorkspaceRouter?.let { router ->
                {
                    val definitions = router.toolDefinitions()
                    RuntimeCapabilitySnapshot(
                        googleWorkspaceConfigured = true,
                        googleWorkspaceToolAccessAvailable = definitions.isNotEmpty(),
                    )
                }
            }
        val koogConfiguration = resolveKoogFeatureRuntimeConfiguration()
        val koogMemory = KoogRuntimeIntegrationFactory.createMemory(koogConfiguration, memoryRuntime)
        val telemetry = KoogRuntimeIntegrationFactory.createTelemetry(koogConfiguration)

        val runtime =
            BertBotRuntime(
                config = runtimeConfig,
                aiRuntimeConfiguration = aiRuntimeConfiguration,
                stateStore = stateStore,
                graph = graph,
                assistantResponseSkill = createAssistantResponseSkill(llmGateway),
                memoryRuntime = memoryRuntime,
                ingestionRuntime = ingestionRuntime,
                researchRuntime = researchRuntime,
                toolCallingSkill = toolCallingSkill,
                checkpointStore = checkpointStore,
                rollbackService = rollbackService,
                stateEventStore = stateEventStore,
                stateReplayService = stateReplayService,
                koogMemory = koogMemory,
                runtimeCapabilitySnapshot = runtimeCapabilitySnapshot,
                runtimeCapabilitySnapshotProvider = runtimeCapabilitySnapshotProvider,
                telemetry = telemetry,
            )
        val connectorRuntime = BertBotConnectorRuntimeFactory.create(runtimeConfig, runtime)
        runtime.attachConnectorRuntime(connectorRuntime)
        return runtime
    }
}

internal data class RuntimeToolIntegration(
    val id: String,
    val toolDefinitionsProvider: () -> List<JsonObject>,
    val toolExecutor: (toolName: String, params: JsonObject) -> Pair<Boolean, String>?,
)

internal data class ToolBackedSubAgentRequirement(
    val subAgentId: String,
    val integrationId: String,
    val required: Boolean,
)

internal val TOOL_BACKED_SUB_AGENT_REQUIREMENTS: List<ToolBackedSubAgentRequirement> =
    listOf(
        ToolBackedSubAgentRequirement(
            subAgentId = "polymarket_analyst",
            integrationId = "polymarket",
            required = true,
        ),
        ToolBackedSubAgentRequirement(
            subAgentId = "google_workspace_operator",
            integrationId = "google_workspace",
            required = false,
        ),
    )

private fun buildToolCallingSkillOrNull(
    googleWorkspaceRouter: GoogleWorkspaceToolRouter?,
    polymarketToolRouter: PolymarketToolRouter?,
    macrofactorToolRouter: MacrofactorToolRouter?,
    llmGateway: com.personalagent.bertbot.llm.LlmGateway,
    config: BertBotAgentConfig,
): ToolCallingSkill? {
    val integrations =
        buildRuntimeToolIntegrations(
            googleWorkspaceRouter = googleWorkspaceRouter,
            polymarketToolRouter = polymarketToolRouter,
            macrofactorToolRouter = macrofactorToolRouter,
        )
    validateToolBackedSubAgentCoverage(config, integrations)
    if (integrations.isEmpty()) return null

    return ToolCallingSkill(
        llmGateway = llmGateway,
        toolDefinitionsProvider = {
            integrations.flatMap { integration -> integration.toolDefinitionsProvider.invoke() }
        },
        toolExecutor = { name, args ->
            val params = JsonObject()
            params.add("arguments", args)

            integrations
                .firstNotNullOfOrNull { integration ->
                    integration.toolExecutor(name, params)?.second
                } ?: "Tool '$name' not found"
        },
    )
}

internal fun buildRuntimeToolIntegrations(
    googleWorkspaceRouter: GoogleWorkspaceToolRouter?,
    polymarketToolRouter: PolymarketToolRouter?,
    macrofactorToolRouter: MacrofactorToolRouter? = null,
): List<RuntimeToolIntegration> {
    val integrations = mutableListOf<RuntimeToolIntegration>()

    if (macrofactorToolRouter != null) {
        integrations +=
            RuntimeToolIntegration(
                id = "macrofactor",
                toolDefinitionsProvider = macrofactorToolRouter::toolDefinitions,
                toolExecutor = { toolName, params -> macrofactorToolRouter.handle(toolName, params) },
            )
    }

    if (googleWorkspaceRouter != null) {
        integrations +=
            RuntimeToolIntegration(
                id = "google_workspace",
                toolDefinitionsProvider = googleWorkspaceRouter::toolDefinitions,
                toolExecutor = { toolName, params -> googleWorkspaceRouter.handle(toolName, params) },
            )
    }

    if (polymarketToolRouter != null) {
        val definitions = polymarketToolDefinitions(polymarketToolRouter)
        integrations +=
            RuntimeToolIntegration(
                id = "polymarket",
                toolDefinitionsProvider = { definitions },
                toolExecutor = { toolName, params -> polymarketToolRouter.handle(toolName, params) },
            )
    }

    return integrations
}

internal fun createMacrofactorToolRouterOrNull(configuration: MacrofactorRuntimeConfiguration): MacrofactorToolRouter? {
    if (!configuration.enabled) return null
    return MacrofactorToolRouter(configuration)
}

internal fun createPolymarketToolRouterOrNull(config: BertBotAgentConfig): PolymarketToolRouter? {
    val polymarketEnabled = config.enabledSubAgents().any { definition -> definition.id == "polymarket_analyst" }
    if (!polymarketEnabled) return null
    return PolymarketToolRouter(PolymarketApiClient.fromEnvironment())
}

internal fun validateToolBackedSubAgentCoverage(
    config: BertBotAgentConfig,
    integrations: List<RuntimeToolIntegration>,
) {
    val enabledSubAgentIds = config.enabledSubAgents().map { definition -> definition.id }.toSet()
    val availableIntegrationIds = integrations.map { integration -> integration.id }.toSet()

    val missingRequired =
        TOOL_BACKED_SUB_AGENT_REQUIREMENTS.filter { requirement ->
            requirement.required &&
                requirement.subAgentId in enabledSubAgentIds &&
                requirement.integrationId !in availableIntegrationIds
        }

    if (missingRequired.isNotEmpty()) {
        val details =
            missingRequired.joinToString(separator = ", ") { requirement ->
                "${requirement.subAgentId}->${requirement.integrationId}"
            }
        check(false) {
            "Missing required runtime tool integrations for enabled sub-agents: $details"
        }
    }
}

internal fun polymarketToolDefinitions(polymarketToolRouter: PolymarketToolRouter?): List<JsonObject> {
    if (polymarketToolRouter == null) return emptyList()

    return listOf(
        polymarketToolDefinition(
            McpConstants.POLYMARKET_GAMMA_TOOL_NAME,
            "Query Polymarket Gamma API public endpoints (markets, events, search).",
        ),
        polymarketToolDefinition(
            McpConstants.POLYMARKET_CLOB_TOOL_NAME,
            "Query Polymarket public CLOB market-data endpoints (book, prices, spreads, history).",
        ),
        polymarketToolDefinition(
            McpConstants.POLYMARKET_DATA_TOOL_NAME,
            "Query Polymarket Data API public analytics endpoints (trades, activity, positions, value, holders, OI, leaderboards).",
        ),
    )
}

private fun polymarketToolDefinition(
    name: String,
    description: String,
): JsonObject =
    JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add(
            "inputSchema",
            JsonObject().apply {
                addProperty("type", "object")
                add(
                    "properties",
                    JsonObject().apply {
                        add(
                            "operation",
                            JsonObject().apply {
                                addProperty("type", "string")
                                addProperty("description", "Operation name for the selected Polymarket API.")
                            },
                        )
                        add(
                            "params",
                            JsonObject().apply {
                                addProperty("type", "object")
                                addProperty("description", "Optional operation-specific arguments.")
                            },
                        )
                    },
                )
                add("required", JsonArray().apply { add("operation") })
            },
        )
    }

internal fun extractDisplayNameFromMessage(message: String): String? {
    val pattern = Regex("""(?i)\bmy\s+name\s+is\s+([A-Za-z][A-Za-z .'-]{0,80})""")
    val match = pattern.find(message) ?: return null
    return match
        .groupValues
        .getOrNull(1)
        ?.trim()
        ?.trimEnd('.', '!', '?', ',', ';', ':')
        ?.takeIf { it.isNotBlank() }
}

internal fun isNameRecallQuestion(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("what is my name") || normalized.contains("do you know my name")
}
