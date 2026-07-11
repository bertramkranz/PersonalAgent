package com.personalagent.bertbot.app

import com.personalagent.bertbot.agents.SelfCorrectingSkill
import com.personalagent.bertbot.agents.SelfCorrectingSkillRequest
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.MaxTurnsExceededException
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
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
    private val interactionGraphWriter: InteractionGraphWriter = InteractionGraphWriter(),
) : AutoCloseable {
    private val requestContextBuilder = BertBotRequestContextBuilder(config, memoryRuntime)

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

    override fun close() {
        memoryRuntime.memoryWorker.close()
    }
}

internal data class BertBotMemoryRuntime(
    val episodicMemory: EpisodicMemory,
    val memoryAssembler: DualMemoryContextAssembler,
    val memoryWorker: MemorySummarizationWorker,
    val userProfileStore: UserProfileStore,
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

        return BertBotRuntime(
            config = config,
            aiRuntimeConfiguration = aiRuntimeConfiguration,
            graph = graph,
            assistantResponseSkill = createAssistantResponseSkill(llmGateway),
            memoryRuntime = memoryRuntime,
        )
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
