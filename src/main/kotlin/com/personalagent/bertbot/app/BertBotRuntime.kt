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

internal class BertBotRuntime(
    val config: BertBotAgentConfig,
    val aiRuntimeConfiguration: AiRuntimeConfiguration,
    private val graph: BertBotGraphRunner,
    private val assistantResponseSkill: SelfCorrectingSkill<AssistantResponseEnvelope>,
    private val memoryRuntime: BertBotMemoryRuntime,
    private val ingestionRuntime: BertBotIngestionRuntime? = null,
) : AutoCloseable {
    private val interactionGraphWriter: InteractionGraphWriter = InteractionGraphWriter()
    private val requestContextBuilder = BertBotRequestContextBuilder(config, memoryRuntime)
    private var connectorRuntime: BertBotConnectorRuntime = BertBotConnectorRuntime()

    fun respondTo(
        userMessage: String,
        emitFallbackMessage: Boolean = true,
        traceCorrelationId: String? = null,
    ): String? {
        val requestContext = requestContextBuilder.build(userMessage, traceCorrelationId)

        val state =
            try {
                graph.run(requestContext.initialState)
            } catch (e: MaxTurnsExceededException) {
                if (emitFallbackMessage) {
                    println("Assistant: ${e.fallbackMessage}")
                    println("")
                }
                return null
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

        interactionGraphWriter.write(
            traceId = tracingContext.traceId,
            state = state,
            events = TraceLogger.snapshot(tracingContext.traceId),
        )

        memoryRuntime.episodicMemory.append("ASSISTANT: $response")
        memoryRuntime.memoryWorker.scheduleIfNeeded()
        return response
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

        val ingestionOutcome = control.ingestManual(messages = listOf(message), dryRun = dryRun).first()
        if (ingestionOutcome.decision != IngestionDecision.APPROVED || message.text.isNullOrBlank()) {
            return ExternalChatOutcome(inbound = message, ingestion = ingestionOutcome, outbound = null, dryRun = dryRun)
        }

        val response =
            respondTo(
                userMessage = "[external:${message.source.platform.name.lowercase()}:${message.source.sourceId}] ${message.text}",
                emitFallbackMessage = false,
                traceCorrelationId = "ext-${message.messageId}",
            ) ?: return ExternalChatOutcome(inbound = message, ingestion = ingestionOutcome, outbound = null, dryRun = dryRun)

        return ExternalChatOutcome(
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

    fun ingestionControlPlane(): IngestionControlPlane? = ingestionRuntime?.controlPlane

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
    }
}

internal data class BertBotMemoryRuntime(
    val episodicMemory: EpisodicMemory,
    val memoryAssembler: DualMemoryContextAssembler,
    val memoryWorker: MemorySummarizationWorker,
    val userProfileStore: UserProfileStore,
)

internal data class BertBotIngestionRuntime(
    val controlPlane: IngestionControlPlane,
    val scheduler: AutoCloseable? = null,
)

internal object BertBotRuntimeFactory {
    fun create(
        config: BertBotAgentConfig = BertBotAgentConfig(),
        aiRuntimeConfiguration: AiRuntimeConfiguration = resolveAiRuntimeConfiguration(),
    ): BertBotRuntime? {
        require(aiRuntimeConfiguration.provider.equals(DEFAULT_AI_PROVIDER, ignoreCase = true)) {
            "Unsupported AI provider '${aiRuntimeConfiguration.provider}'. Supported providers: $DEFAULT_AI_PROVIDER."
        }

        val apiKey = aiRuntimeConfiguration.apiKey ?: return null

        val stateStore = BertBotRuntimeDependenciesFactory.createStateStore()
        val graph = BertBotApplication.createGraph(stateStore, config)
        val llmGateway = createOpenAiLlmGateway(apiKey, aiRuntimeConfiguration.model)
        val memoryRuntime = BertBotRuntimeDependenciesFactory.createMemoryRuntime(config, llmGateway)
        val ingestionRuntime = BertBotRuntimeDependenciesFactory.createIngestionRuntime(config, memoryRuntime)

        val runtime =
            BertBotRuntime(
                config = config,
                aiRuntimeConfiguration = aiRuntimeConfiguration,
                graph = graph,
                assistantResponseSkill = createAssistantResponseSkill(llmGateway),
                memoryRuntime = memoryRuntime,
                ingestionRuntime = ingestionRuntime,
            )
        val connectorRuntime = BertBotConnectorRuntimeFactory.create(config, runtime)
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
