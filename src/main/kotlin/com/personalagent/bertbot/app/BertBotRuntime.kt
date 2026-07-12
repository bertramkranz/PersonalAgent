package com.personalagent.bertbot.app

import com.personalagent.bertbot.agents.SelfCorrectingSkill
import com.personalagent.bertbot.agents.SelfCorrectingSkillRequest
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.MaxTurnsExceededException
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
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
) : AutoCloseable {
    private val interactionGraphWriter: InteractionGraphWriter = InteractionGraphWriter()
    private val requestContextBuilder = BertBotRequestContextBuilder(config, memoryRuntime)
    private var connectorRuntime: BertBotConnectorRuntime = BertBotConnectorRuntime()

    fun respondTo(
        userMessage: String,
        emitFallbackMessage: Boolean = true,
        traceCorrelationId: String? = null,
        persistenceScopeKey: String = DEFAULT_PERSISTENCE_SCOPE_KEY,
    ): String? {
        return withPersistenceScope(persistenceScopeKey) {
            if (isLikelyPromptInjection(userMessage)) {
                return@withPersistenceScope promptInjectionRefusalMessage()
            }

            val requestContext = requestContextBuilder.build(userMessage, traceCorrelationId)

            val state =
                try {
                    graph.run(requestContext.initialState)
                } catch (e: MaxTurnsExceededException) {
                    if (emitFallbackMessage) {
                        println("Assistant: ${e.fallbackMessage}")
                        println("")
                    }
                    return@withPersistenceScope null
                }

            val systemPrompt = buildSystemPrompt(config, state)
            val tracingContext = TracingContext(traceId = state.traceId ?: requestContext.requestTraceId)
            val response =
                if (isNameRecallQuestion(userMessage) && !requestContext.knownProfile.displayName.isNullOrBlank()) {
                    TraceLogger.info(tracingContext, "profile_lookup", "resolved_name=true")
                    "Your name is ${requestContext.knownProfile.displayName}."
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
            memoryRuntime.memoryWorker.scheduleIfNeeded()
            runCatching {
                researchRuntime?.service?.submitEventAsync(reason = "respond_to")
            }
            response
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
    ): ExternalChatOutcome {
        val control = ingestionRuntime?.controlPlane
        if (control == null) {
            val skipped = IngestionOutcome(message = message, decision = IngestionDecision.SKIPPED_UNAPPROVED, dryRun = dryRun)
            return ExternalChatOutcome(inbound = message, ingestion = skipped, outbound = null, dryRun = dryRun)
        }

        val scopeKey = buildExternalScopeKey(message)
        return withPersistenceScope(scopeKey) {
            val ingestionOutcome = control.ingestManual(messages = listOf(message), dryRun = dryRun).first()
            if (ingestionOutcome.decision != IngestionDecision.APPROVED || message.text.isNullOrBlank()) {
                return@withPersistenceScope ExternalChatOutcome(inbound = message, ingestion = ingestionOutcome, outbound = null, dryRun = dryRun)
            }

            val response =
                respondTo(
                    userMessage = "[external:${message.source.platform.name.lowercase()}:${message.source.sourceId}] ${message.text}",
                    emitFallbackMessage = false,
                    traceCorrelationId = "ext-${message.messageId}",
                    persistenceScopeKey = scopeKey,
                ) ?: return@withPersistenceScope ExternalChatOutcome(inbound = message, ingestion = ingestionOutcome, outbound = null, dryRun = dryRun)

            ExternalChatOutcome(
                inbound = message,
                ingestion = ingestionOutcome,
                outbound =
                    NormalizedOutboundMessage(
                        source = message.source,
                        text = response,
                        replyToMessageId = message.messageId,
                        threadId = message.threadId,
                    ),
                dryRun = dryRun,
            )
        }
    }

    fun ingestionControlPlane(): IngestionControlPlane? = ingestionRuntime?.controlPlane

    fun researchService(): ContinuousImprovementResearchService? = researchRuntime?.service

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
                ),
        )

    override fun close() {
        memoryRuntime.memoryWorker.close()
        ingestionRuntime?.scheduler?.close()
        researchRuntime?.scheduler?.close()
        researchRuntime?.service?.close()
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

    private fun buildExternalScopeKey(message: NormalizedIngestionMessage): String {
        val workspace = message.source.workspaceId ?: "none"
        val thread = message.threadId ?: "root"
        return listOf(
            "external",
            message.source.platform.name.lowercase(),
            message.source.sourceKind.name.lowercase(),
            workspace,
            message.source.sourceId,
            thread,
        ).joinToString("|")
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
    fun create(
        config: BertBotAgentConfig = BertBotAgentConfig(),
        aiRuntimeConfiguration: AiRuntimeConfiguration = resolveAiRuntimeConfiguration(),
        workspaceRoot: java.io.File = resolveWorkspaceRoot(),
        enablePeriodicResearchScheduler: Boolean = false,
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
        val graph = BertBotApplication.createGraph(stateStore, runtimeConfig)
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
            )
        val connectorRuntime = BertBotConnectorRuntimeFactory.create(runtimeConfig, runtime)
        runtime.attachConnectorRuntime(connectorRuntime)
        return runtime
    }
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
