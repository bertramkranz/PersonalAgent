@file:Suppress("TooManyFunctions")

package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.personalagent.bertbot.agents.SelfCorrectingSkill
import com.personalagent.bertbot.agents.SelfCorrectingSkillRequest
import com.personalagent.bertbot.agents.ToolCallingSkill
import com.personalagent.bertbot.agents.ToolCallingSkillConfig
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.ExecutionProfileFallbackBehavior
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
import com.personalagent.bertbot.ingestion.connectors.ExternalChatFollowupSender
import com.personalagent.bertbot.ingestion.connectors.ExternalChatPayloadDispatcher
import com.personalagent.bertbot.ingestion.connectors.ManagedExternalChatAsyncRunner
import com.personalagent.bertbot.ingestion.connectors.NoopExternalChatFollowupSender
import com.personalagent.bertbot.llm.GatewayResolution
import com.personalagent.bertbot.llm.LlmGateway
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
    private val sessionHistoryStore: SessionHistoryStore? = null,
    private val learningReviewStore: LearningReviewStore? = null,
    private val learningReviewConfiguration: LearningReviewRuntimeConfiguration = LearningReviewRuntimeConfiguration(),
    private val koogMemory: KoogMemoryIntegration = KoogMemoryIntegration(),
    private val toolCapabilityRegistry: CapabilityRegistry? = null,
    private val runtimeCapabilitySnapshot: RuntimeCapabilitySnapshot = RuntimeCapabilitySnapshot(),
    private val runtimeCapabilitySnapshotProvider: (() -> RuntimeCapabilitySnapshot)? = null,
    private val telemetry: RuntimeTelemetry = NoOpRuntimeTelemetry,
) : AutoCloseable {
    private val interactionGraphWriter: InteractionGraphWriter = InteractionGraphWriter()
    private val requestContextBuilder = BertBotRequestContextBuilder(config, memoryRuntime)
    private var externalChatAsyncRunner: ManagedExternalChatAsyncRunner? = null
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
                sessionHistoryStore?.append(
                    buildSessionHistoryEntry(
                        role = SessionHistoryRole.USER,
                        text = userMessage,
                        traceId = tracingContext.traceId,
                    ),
                )
                val response =
                    generateAssistantResponse(
                        userMessage = userMessage,
                        requestContext = requestContext,
                        state = state,
                        systemPrompt = systemPrompt,
                        tracingContext = tracingContext,
                    )

                runCatching {
                    interactionGraphWriter.write(
                        traceId = tracingContext.traceId,
                        state = state,
                        events = TraceLogger.snapshot(tracingContext.traceId),
                    )
                }.onFailure { e ->
                    TraceLogger.warn(tracingContext, "diagram-write-failed", "InteractionGraphWriter failed: ${e.message}")
                }

                if (!shouldGateMemoryWrites()) {
                    memoryRuntime.episodicMemory.append("ASSISTANT: $response")
                } else {
                    enqueueLearningReviewRequest(
                        scopeKey = persistenceScopeKey,
                        writeType = LearningReviewWriteType.MEMORY,
                        payload =
                            learningReviewJson.toJson(
                                MemoryWriteApprovalPayload(
                                    userMessage = userMessage,
                                    assistantResponse = response,
                                    traceId = tracingContext.traceId,
                                ),
                            ),
                        traceId = tracingContext.traceId,
                    )
                }
                sessionHistoryStore?.append(
                    buildSessionHistoryEntry(
                        role = SessionHistoryRole.ASSISTANT,
                        text = response,
                        traceId = tracingContext.traceId,
                    ),
                )
                if (!shouldGateMemoryWrites()) {
                    runCatching {
                        koogMemory.recordTurn(
                            scopeKey = persistenceScopeKey,
                            userMessage = userMessage,
                            assistantResponse = response,
                            traceId = tracingContext.traceId,
                        )
                    }
                    memoryRuntime.memoryWorker.scheduleIfNeeded()
                }
                if (!shouldGateSkillWrites()) {
                    runCatching {
                        researchRuntime?.service?.submitEventAsync(reason = "respond_to")
                    }
                } else {
                    enqueueLearningReviewRequest(
                        scopeKey = persistenceScopeKey,
                        writeType = LearningReviewWriteType.SKILL,
                        payload = learningReviewJson.toJson(SkillWriteApprovalPayload(reason = "respond_to")),
                        traceId = tracingContext.traceId,
                    )
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

    fun listSessionHistory(
        limit: Int = 200,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): List<SessionHistoryEntry> {
        val store = sessionHistoryStore ?: return emptyList()
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.withScope(normalizedScopeKey) { store.list(limit) }
    }

    fun clearSessionHistory(persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY): Boolean {
        val store = sessionHistoryStore ?: return false
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.withScope(normalizedScopeKey) {
            store.clear()
            true
        }
    }

    fun searchSessionHistory(
        query: String,
        limit: Int = 50,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): List<SessionHistoryEntry> {
        val store = sessionHistoryStore ?: return emptyList()
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.withScope(normalizedScopeKey) {
            store.search(query = query, limit = limit)
        }
    }

    fun listPendingLearningReviewRequests(
        limit: Int = 200,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): List<LearningReviewRequest> {
        val store = learningReviewStore ?: return emptyList()
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.withScope(normalizedScopeKey) {
            store
                .list(status = LearningReviewStatus.PENDING, limit = limit)
                .sortedByDescending { it.lastApplyFailedAt ?: "" }
        }
    }

    fun approveLearningReviewRequest(
        requestId: String,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
        note: String? = null,
    ): LearningReviewDecisionResult {
        val store = learningReviewStore ?: return LearningReviewDecisionResult(message = "Learning review store unavailable.")
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.withScope(normalizedScopeKey) {
            val pendingRequest =
                store
                    .list(status = LearningReviewStatus.PENDING, limit = 10_000)
                    .firstOrNull { it.requestId == requestId }
                    ?: return@withScope LearningReviewDecisionResult(message = "Learning review request not found.")
            val applyResult = applyLearningReviewRequest(pendingRequest)
            if (!applyResult.success) {
                val failureReason = applyResult.message ?: "apply failed"
                store.recordApplyFailure(requestId = requestId, reason = failureReason)
                return@withScope LearningReviewDecisionResult(message = failureReason)
            }
            val decided = store.decide(requestId = requestId, status = LearningReviewStatus.APPROVED, note = note)
            LearningReviewDecisionResult(request = decided)
        }
    }

    fun rejectLearningReviewRequest(
        requestId: String,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
        note: String? = null,
    ): LearningReviewDecisionResult {
        val store = learningReviewStore ?: return LearningReviewDecisionResult(message = "Learning review store unavailable.")
        val normalizedScopeKey = normalizeScopeKey(persistenceScopeKey)
        return store.withScope(normalizedScopeKey) {
            val decided = store.decide(requestId = requestId, status = LearningReviewStatus.REJECTED, note = note)
            if (decided == null) {
                LearningReviewDecisionResult(message = "Learning review request not found.")
            } else {
                LearningReviewDecisionResult(request = decided)
            }
        }
    }

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

    fun externalPayloadDispatcher(followupSender: ExternalChatFollowupSender = NoopExternalChatFollowupSender): ExternalChatPayloadDispatcher =
        ExternalChatPayloadDispatcher(
            connectors =
                BertBotExternalConnectors(
                    telegram = connectorRuntime.telegram,
                    slack = connectorRuntime.slack,
                    whatsapp = connectorRuntime.whatsapp,
                    discord = connectorRuntime.discord,
                ),
            followupSender = followupSender,
            asyncRunner = ensureExternalChatAsyncRunner(),
        )

    override fun close() {
        memoryRuntime.memoryWorker.close()
        ingestionRuntime?.scheduler?.close()
        researchRuntime?.scheduler?.close()
        researchRuntime?.service?.close()
        externalChatAsyncRunner?.close()
        telemetry.close()
    }

    private fun ensureExternalChatAsyncRunner(): ManagedExternalChatAsyncRunner {
        val existing = externalChatAsyncRunner
        if (existing != null) {
            return existing
        }
        return ManagedExternalChatAsyncRunner().also { runner ->
            externalChatAsyncRunner = runner
        }
    }

    private fun <T> withPersistenceScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val normalizedScopeKey = normalizeScopeKey(scopeKey)
        return stateStore.withScope(normalizedScopeKey) {
            memoryRuntime.episodicMemory.withScope(normalizedScopeKey) {
                memoryRuntime.semanticMemory.withScope(normalizedScopeKey) {
                    memoryRuntime.userProfileStore.withScope(normalizedScopeKey) {
                        val runInsideSessionScope: (() -> T) -> T = { innerAction ->
                            sessionHistoryStore?.withScope(normalizedScopeKey, innerAction) ?: innerAction()
                        }
                        val runInsideLearningReviewScope: (() -> T) -> T = { innerAction ->
                            learningReviewStore?.withScope(normalizedScopeKey, innerAction) ?: innerAction()
                        }
                        runInsideSessionScope {
                            runInsideLearningReviewScope {
                                action()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enqueueLearningReviewRequest(
        scopeKey: String,
        writeType: LearningReviewWriteType,
        payload: String,
        traceId: String?,
    ) {
        val store = learningReviewStore ?: return
        val normalizedScopeKey = normalizeScopeKey(scopeKey)
        store.withScope(normalizedScopeKey) {
            store.enqueue(
                buildLearningReviewRequest(
                    scopeKey = normalizedScopeKey,
                    writeType = writeType,
                    payload = payload,
                    traceId = traceId,
                ),
            )
        }
    }

    private fun applyLearningReviewRequest(request: LearningReviewRequest): LearningReviewApplyResult {
        when (request.writeType) {
            LearningReviewWriteType.MEMORY -> {
                val payload =
                    runCatching {
                        learningReviewJson.fromJson(request.payload, MemoryWriteApprovalPayload::class.java)
                    }.getOrNull()
                        ?: return LearningReviewApplyResult(
                            success = false,
                            message = "memory payload parsing failed",
                        )
                memoryRuntime.episodicMemory.append("ASSISTANT: ${payload.assistantResponse}")
                runCatching {
                    koogMemory.recordTurn(
                        scopeKey = request.scopeKey,
                        userMessage = payload.userMessage,
                        assistantResponse = payload.assistantResponse,
                        traceId = payload.traceId ?: request.traceId ?: "learning-review",
                    )
                }
                memoryRuntime.memoryWorker.scheduleIfNeeded()
                return LearningReviewApplyResult(success = true)
            }
            LearningReviewWriteType.SKILL -> {
                val payload =
                    runCatching {
                        learningReviewJson.fromJson(request.payload, SkillWriteApprovalPayload::class.java)
                    }.getOrNull()
                        ?: return LearningReviewApplyResult(
                            success = false,
                            message = "skill payload parsing failed",
                        )
                val service =
                    researchRuntime?.service
                        ?: return LearningReviewApplyResult(
                            success = false,
                            message = "research service unavailable",
                        )
                return runCatching {
                    service.submitEventAsync(reason = payload.reason)
                    LearningReviewApplyResult(success = true)
                }
                    .getOrElse { error ->
                        LearningReviewApplyResult(
                            success = false,
                            message = "skill apply failed: ${error.message ?: "unknown error"}",
                        )
                    }
            }
        }
    }

    private fun normalizeScopeKey(scopeKey: String): String {
        val normalized = scopeKey.trim().ifBlank { DEFAULT_PERSISTENCE_SCOPE_KEY }
        return normalized.replace("|", "_")
    }

    private fun generateAssistantResponse(
        userMessage: String,
        requestContext: BertBotRequestContext,
        state: BertBotState,
        systemPrompt: String,
        tracingContext: TracingContext,
    ): String {
        if (isNameRecallQuestion(userMessage) && !requestContext.knownProfile.displayName.isNullOrBlank()) {
            TraceLogger.info(tracingContext, "profile_lookup", "resolved_name=true")
            return "Your name is ${requestContext.knownProfile.displayName}."
        }

        if (toolCallingSkill == null) {
            return assistantResponseSkill
                .invoke(
                    input =
                        SelfCorrectingSkillRequest(
                            systemPrompt = systemPrompt,
                            userPrompt = userMessage,
                        ),
                    tracingContext = tracingContext,
                    selectedModelId = state.selectedModel ?: state.modelRoutingDecision?.selectedModelId,
                ).response
        }

        val allowedCapabilityIds = resolveAllowedCapabilityIdsForSelectedSubAgent(state.selectedSubAgent)
        val filteredDefinitions =
            when {
                toolCapabilityRegistry == null -> null
                allowedCapabilityIds == null -> null
                else -> toolCapabilityRegistry.toolDefinitionsForCapabilities(allowedCapabilityIds)
            }

        return toolCallingSkill.invoke(
            systemPrompt = systemPrompt,
            userPrompt = userMessage,
            tracingContext = tracingContext,
            dynamicToolDefinitions = filteredDefinitions,
            dynamicToolExecutor =
                if (toolCapabilityRegistry == null) {
                    null
                } else {
                    { name, args -> executeProfileScopedTool(name, args, state.selectedSubAgent, allowedCapabilityIds) }
                },
            selectedModelId = state.selectedModel ?: state.modelRoutingDecision?.selectedModelId,
        )
    }

    private fun resolveAllowedCapabilityIdsForSelectedSubAgent(selectedSubAgentId: String?): Set<String>? {
        val registry = toolCapabilityRegistry ?: return null
        val profile = config.executionProfileFor(selectedSubAgentId) ?: return null
        val availableIds = registry.capabilityIds()
        val declared = (profile.requiredCapabilities + profile.optionalCapabilities).filterTo(mutableSetOf()) { it in availableIds }
        if (declared.isNotEmpty()) {
            return declared
        }

        return when (profile.fallbackBehavior) {
            ExecutionProfileFallbackBehavior.DENY_OUTSIDE_PROFILE -> emptySet()
            ExecutionProfileFallbackBehavior.ALLOW_ALL,
            ExecutionProfileFallbackBehavior.WARN_ONLY,
            -> null
        }
    }

    private fun executeProfileScopedTool(
        name: String,
        args: JsonObject,
        selectedSubAgentId: String?,
        allowedCapabilityIds: Set<String>?,
    ): String {
        val registry = toolCapabilityRegistry ?: return "Tool '$name' not found"
        if (allowedCapabilityIds != null) {
            val capabilityId = registry.capabilityIdForToolName(name)
            if (capabilityId != null && capabilityId !in allowedCapabilityIds) {
                val profileId = selectedSubAgentId ?: "unscoped"
                return "Tool '$name' is not available for sub-agent profile '$profileId'."
            }
        }

        val params = JsonObject()
        params.add("arguments", args)
        return registry.execute(name, params, allowedCapabilityIds)?.second ?: "Tool '$name' not found"
    }

    private fun shouldGateMemoryWrites(): Boolean =
        learningReviewConfiguration.enabled && learningReviewConfiguration.memoryWriteApprovalRequired

    private fun shouldGateSkillWrites(): Boolean =
        learningReviewConfiguration.enabled && learningReviewConfiguration.skillWriteApprovalRequired

    private companion object {
        private const val DEFAULT_PERSISTENCE_SCOPE_KEY = "global"
        private val learningReviewJson = Gson()
    }
}

private data class MemoryWriteApprovalPayload(
    val userMessage: String,
    val assistantResponse: String,
    val traceId: String? = null,
)

private data class SkillWriteApprovalPayload(
    val reason: String,
)

internal data class LearningReviewApplyResult(
    val success: Boolean,
    val message: String? = null,
)

internal data class LearningReviewDecisionResult(
    val request: LearningReviewRequest? = null,
    val message: String? = null,
)

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
        val sessionHistoryConfiguration = resolveSessionHistoryRuntimeConfiguration()
        val learningReviewConfiguration = resolveLearningReviewRuntimeConfiguration()
        val stateStore = BertBotRuntimeDependenciesFactory.createStateStore(persistenceConfiguration)
        val checkpointStore = BertBotRuntimeDependenciesFactory.createCheckpointStore(persistenceConfiguration)
        val stateEventStore = BertBotRuntimeDependenciesFactory.createStateEventStore(persistenceConfiguration)
        val sessionHistoryStore =
            if (sessionHistoryConfiguration.enabled) {
                BertBotRuntimeDependenciesFactory.createSessionHistoryStore(
                    persistenceConfiguration = persistenceConfiguration,
                    sessionHistoryConfiguration = sessionHistoryConfiguration,
                )
            } else {
                null
            }
        val learningReviewStore = BertBotRuntimeDependenciesFactory.createLearningReviewStore(persistenceConfiguration)
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
        val gatewayResolver: (String?) -> GatewayResolution = { selectedModelId ->
            resolveRuntimeGatewayForModel(aiRuntimeConfiguration, llmGateway, selectedModelId)
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
        val shoppingRuntimeConfiguration = resolveShoppingRuntimeConfiguration()
        validateShoppingConfiguration(runtimeConfig, shoppingRuntimeConfiguration)
        val macrofactorToolRouter = createMacrofactorToolRouterOrNull(resolveMacrofactorRuntimeConfiguration())
        val polymarketToolRouter = createPolymarketToolRouterOrNull(runtimeConfig)
        val shoppingToolRouter = createShoppingToolRouterOrNull(shoppingRuntimeConfiguration)
        val sessionHistoryToolRouter =
            sessionHistoryStore?.let { store ->
                SessionHistoryToolRouter(
                    listEntries = { limit, scopeKey ->
                        runtimeScopedSessionHistoryList(store, scopeKey, limit)
                    },
                    searchEntries = { query, limit, scopeKey ->
                        runtimeScopedSessionHistorySearch(store, scopeKey, query, limit)
                    },
                    clearEntries = { scopeKey ->
                        runtimeScopedSessionHistoryClear(store, scopeKey)
                    },
                )
            }
        val capabilityRegistry =
            buildCapabilityRegistry(
                googleWorkspaceRouter = googleWorkspaceRouter,
                polymarketToolRouter = polymarketToolRouter,
                macrofactorToolRouter = macrofactorToolRouter,
                shoppingToolRouter = shoppingToolRouter,
                sessionHistoryToolRouter = sessionHistoryToolRouter,
            )
        val googleWorkspaceToolDefinitions = googleWorkspaceRouter?.toolDefinitions().orEmpty()
        val toolCallingSkill =
            buildToolCallingSkillOrNull(
                capabilityRegistry = capabilityRegistry,
                llmGateway = llmGateway,
                config = runtimeConfig,
                gatewayResolver = gatewayResolver,
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
                assistantResponseSkill = createAssistantResponseSkill(llmGateway, gatewayResolver),
                memoryRuntime = memoryRuntime,
                ingestionRuntime = ingestionRuntime,
                researchRuntime = researchRuntime,
                toolCallingSkill = toolCallingSkill,
                checkpointStore = checkpointStore,
                rollbackService = rollbackService,
                stateEventStore = stateEventStore,
                stateReplayService = stateReplayService,
                sessionHistoryStore = sessionHistoryStore,
                learningReviewStore = learningReviewStore,
                learningReviewConfiguration = learningReviewConfiguration,
                koogMemory = koogMemory,
                toolCapabilityRegistry = capabilityRegistry,
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
    capabilityRegistry: CapabilityRegistry,
    llmGateway: com.personalagent.bertbot.llm.LlmGateway,
    config: BertBotAgentConfig,
    gatewayResolver: ((String?) -> GatewayResolution)? = null,
): ToolCallingSkill? {
    validateToolBackedSubAgentCoverage(config, capabilityRegistry)
    if (capabilityRegistry.capabilityIds().isEmpty()) return null

    return ToolCallingSkill(
        config =
            ToolCallingSkillConfig(
                llmGateway = llmGateway,
                toolDefinitionsProvider = capabilityRegistry::toolDefinitions,
                toolExecutor = { name, args ->
                    val params = JsonObject()
                    params.add("arguments", args)
                    capabilityRegistry.execute(name, params)?.second ?: "Tool '$name' not found"
                },
                gatewayResolver = gatewayResolver,
            ),
    )
}

internal fun resolveRuntimeGatewayForModel(
    aiRuntimeConfiguration: AiRuntimeConfiguration,
    fallbackGateway: com.personalagent.bertbot.llm.LlmGateway,
    selectedModelId: String?,
): GatewayResolution {
    val requestedModel = selectedModelId?.takeIf { it.isNotBlank() }
    if (requestedModel.isNullOrBlank() || requestedModel == aiRuntimeConfiguration.model) {
        return GatewayResolution(
            gateway = fallbackGateway,
            requestedModelId = requestedModel,
            effectiveModelId = aiRuntimeConfiguration.model,
        )
    }

    return when (aiRuntimeConfiguration.provider.lowercase()) {
        "openai" -> {
            val apiKey =
                aiRuntimeConfiguration.apiKey
                    ?: return GatewayResolution(
                        gateway = fallbackGateway,
                        requestedModelId = requestedModel,
                        effectiveModelId = aiRuntimeConfiguration.model,
                        fallbackReason = "openai_api_key_missing",
                    )
            runCatching { createOpenAiLlmGateway(apiKey, requestedModel) }
                .map { gateway ->
                    GatewayResolution(
                        gateway = gateway,
                        requestedModelId = requestedModel,
                        effectiveModelId = requestedModel,
                    )
                }.getOrElse {
                    GatewayResolution(
                        gateway = fallbackGateway,
                        requestedModelId = requestedModel,
                        effectiveModelId = aiRuntimeConfiguration.model,
                        fallbackReason = "openai_model_resolution_failed",
                    )
                }
        }
        "ollama" -> {
            runCatching {
                createOllamaLlmGateway(
                    baseUrl = aiRuntimeConfiguration.ollamaBaseUrl,
                    modelName = requestedModel,
                    timeoutSeconds = aiRuntimeConfiguration.ollamaTimeoutSeconds,
                )
            }.map { gateway ->
                GatewayResolution(
                    gateway = gateway,
                    requestedModelId = requestedModel,
                    effectiveModelId = requestedModel,
                )
            }.getOrElse {
                GatewayResolution(
                    gateway = fallbackGateway,
                    requestedModelId = requestedModel,
                    effectiveModelId = aiRuntimeConfiguration.model,
                    fallbackReason = "ollama_model_resolution_failed",
                )
            }
        }
        else ->
            GatewayResolution(
                gateway = fallbackGateway,
                requestedModelId = requestedModel,
                effectiveModelId = aiRuntimeConfiguration.model,
                fallbackReason = "unsupported_provider:${aiRuntimeConfiguration.provider}",
            )
    }
}

@Suppress("LongParameterList", "LongMethod")
internal fun buildCapabilityRegistry(
    googleWorkspaceRouter: GoogleWorkspaceToolRouter?,
    polymarketToolRouter: PolymarketToolRouter?,
    macrofactorToolRouter: MacrofactorToolRouter? = null,
    shoppingToolRouter: ShoppingToolRouter? = null,
    sessionHistoryToolRouter: SessionHistoryToolRouter? = null,
    learningReviewToolRouter: LearningReviewToolRouter? = null,
): CapabilityRegistry {
    val capabilities = mutableListOf<CapabilityDefinition>()

    if (macrofactorToolRouter != null) {
        capabilities +=
            CapabilityDefinition(
                id = "macrofactor",
                router =
                    FunctionToolRouter(
                        id = "macrofactor",
                        definitionsProvider = macrofactorToolRouter::toolDefinitions,
                        executor = { toolName, params -> macrofactorToolRouter.handle(toolName, params) },
                    ),
            )
    }

    if (googleWorkspaceRouter != null) {
        capabilities +=
            CapabilityDefinition(
                id = "google_workspace",
                router =
                    FunctionToolRouter(
                        id = "google_workspace",
                        definitionsProvider = googleWorkspaceRouter::toolDefinitions,
                        executor = { toolName, params -> googleWorkspaceRouter.handle(toolName, params) },
                    ),
            )
    }

    if (polymarketToolRouter != null) {
        val definitions = polymarketToolDefinitions(polymarketToolRouter)
        capabilities +=
            CapabilityDefinition(
                id = "polymarket",
                router =
                    FunctionToolRouter(
                        id = "polymarket",
                        definitionsProvider = { definitions },
                        executor = { toolName, params ->
                            if (!isPolymarketToolName(toolName)) {
                                null
                            } else {
                                polymarketToolRouter.handle(toolName.orEmpty(), params)
                            }
                        },
                    ),
            )
    }

    if (shoppingToolRouter != null) {
        capabilities +=
            CapabilityDefinition(
                id = "shopping",
                router =
                    FunctionToolRouter(
                        id = "shopping",
                        definitionsProvider = shoppingToolRouter::toolDefinitions,
                        executor = { toolName, params -> shoppingToolRouter.handle(toolName, params) },
                    ),
            )
    }

    if (sessionHistoryToolRouter != null) {
        capabilities +=
            CapabilityDefinition(
                id = "session_history",
                router =
                    FunctionToolRouter(
                        id = "session_history",
                        definitionsProvider = sessionHistoryToolRouter::toolDefinitions,
                        executor = { toolName, params -> sessionHistoryToolRouter.handle(toolName, params) },
                    ),
            )
    }

    if (learningReviewToolRouter != null) {
        capabilities +=
            CapabilityDefinition(
                id = "learning_review",
                router =
                    FunctionToolRouter(
                        id = "learning_review",
                        definitionsProvider = learningReviewToolRouter::toolDefinitions,
                        executor = { toolName, params -> learningReviewToolRouter.handle(toolName, params) },
                    ),
            )
    }

    return CapabilityRegistry(capabilities)
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

internal fun createShoppingToolRouterOrNull(configuration: ShoppingRuntimeConfiguration): ShoppingToolRouter? {
    if (!configuration.hasEnabledStore) return null
    return ShoppingToolRouter(configuration)
}

private fun runtimeScopedSessionHistoryList(
    store: SessionHistoryStore,
    scopeKey: String?,
    limit: Int,
): List<SessionHistoryEntry> {
    if (scopeKey.isNullOrBlank()) {
        return store.list(limit)
    }
    val normalizedScopeKey = scopeKey.trim().ifBlank { "global" }.replace("|", "_")
    return store.withScope(normalizedScopeKey) { store.list(limit) }
}

private fun runtimeScopedSessionHistorySearch(
    store: SessionHistoryStore,
    scopeKey: String?,
    query: String,
    limit: Int,
): List<SessionHistoryEntry> {
    if (scopeKey.isNullOrBlank()) {
        return store.search(query, limit)
    }
    val normalizedScopeKey = scopeKey.trim().ifBlank { "global" }.replace("|", "_")
    return store.withScope(normalizedScopeKey) { store.search(query, limit) }
}

private fun runtimeScopedSessionHistoryClear(
    store: SessionHistoryStore,
    scopeKey: String?,
): Boolean {
    if (scopeKey.isNullOrBlank()) {
        store.clear()
        return true
    }
    val normalizedScopeKey = scopeKey.trim().ifBlank { "global" }.replace("|", "_")
    return store.withScope(normalizedScopeKey) {
        store.clear()
        true
    }
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

internal fun validateToolBackedSubAgentCoverage(
    config: BertBotAgentConfig,
    capabilityRegistry: CapabilityRegistry,
) {
    val enabledSubAgentIds = config.enabledSubAgents().map { definition -> definition.id }.toSet()
    val availableIntegrationIds = capabilityRegistry.capabilityIds()

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

    val availableCapabilityIds = capabilityRegistry.capabilityIds()
    val missingFromProfiles =
        config.executionProfiles
            .filter { profile -> profile.subAgentId in enabledSubAgentIds }
            .flatMap { profile ->
                profile.requiredCapabilities
                    .filter { capabilityId -> capabilityId !in availableCapabilityIds }
                    .map { capabilityId -> "${profile.subAgentId}->$capabilityId" }
            }

    if (missingFromProfiles.isNotEmpty()) {
        check(false) {
            "Missing required capabilities from execution profiles: ${missingFromProfiles.joinToString()}"
        }
    }
}

internal fun validateShoppingConfiguration(
    config: BertBotAgentConfig,
    shoppingConfiguration: ShoppingRuntimeConfiguration,
) {
    val personalShopperEnabled = config.enabledSubAgents().any { it.id == "personal_shopper" }
    if (personalShopperEnabled && !shoppingConfiguration.hasEnabledStore) {
        error(
            "personal_shopper sub-agent is enabled but no shopping store provider is configured and enabled. " +
                "Configure at least one store via BERTBOT_SHOPPING_STORE_1_ENABLED=true.",
        )
    }
}

internal fun polymarketToolDefinitions(polymarketToolRouter: PolymarketToolRouter?): List<JsonObject> {
    if (polymarketToolRouter == null) return emptyList()

    return listOf(
        polymarketToolDefinition(
            McpConstants.POLYMARKET_GAMMA_TOOL_NAME,
            "Query Polymarket Gamma API public endpoints (markets, events, search).",
            operationOptions =
                listOf(
                    "list_markets",
                    "list_events",
                    "get_market_by_slug",
                    "get_event_by_slug",
                    "search",
                    "list_markets_keyset",
                    "list_events_keyset",
                ),
        ),
        polymarketToolDefinition(
            McpConstants.POLYMARKET_CLOB_TOOL_NAME,
            "Query Polymarket public CLOB market-data endpoints (book, prices, spreads, history).",
            operationOptions =
                listOf(
                    "get_book",
                    "get_price",
                    "get_midpoint",
                    "get_spread",
                    "get_last_trade_price",
                    "get_prices_history",
                ),
        ),
        polymarketToolDefinition(
            McpConstants.POLYMARKET_DATA_TOOL_NAME,
            "Query Polymarket Data API public analytics endpoints (trades, activity, positions, value, holders, OI, leaderboards).",
            operationOptions =
                listOf(
                    "get_trades",
                    "get_activity",
                    "get_positions",
                    "get_value",
                    "get_holders",
                    "get_open_interest",
                    "get_trader_leaderboard",
                    "get_builder_leaderboard",
                ),
        ),
    )
}

internal fun isPolymarketToolName(toolName: String?): Boolean =
    toolName == McpConstants.POLYMARKET_GAMMA_TOOL_NAME ||
        toolName == McpConstants.POLYMARKET_CLOB_TOOL_NAME ||
        toolName == McpConstants.POLYMARKET_DATA_TOOL_NAME

private fun polymarketToolDefinition(
    name: String,
    description: String,
    operationOptions: List<String>,
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
                                addProperty("description", "Operation name for the selected Polymarket API. Must be one of the supported values.")
                                add(
                                    "enum",
                                    JsonArray().apply {
                                        operationOptions.forEach { option ->
                                            add(option)
                                        }
                                    },
                                )
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
