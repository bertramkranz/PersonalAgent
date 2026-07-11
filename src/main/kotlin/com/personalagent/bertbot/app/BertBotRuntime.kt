package com.personalagent.bertbot.app

import com.personalagent.bertbot.agents.SelfCorrectingSkill
import com.personalagent.bertbot.agents.SelfCorrectingSkillRequest
import com.personalagent.bertbot.config.KoogAgentConfig
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.MaxTurnsExceededException
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.LlmMemorySummarizer
import com.personalagent.bertbot.memory.MemorySummarizationWorker
import com.personalagent.bertbot.memory.SafeMemorySummarizer
import com.personalagent.bertbot.memory.SemanticMemory
import java.io.File

internal class BertBotRuntime(
    val config: KoogAgentConfig,
    val aiRuntimeConfiguration: AiRuntimeConfiguration,
    private val graph: BertBotGraphRunner,
    private val assistantResponseSkill: SelfCorrectingSkill<AssistantResponseEnvelope>,
    private val memoryRuntime: BertBotMemoryRuntime,
) : AutoCloseable {
    fun respondTo(
        userMessage: String,
        emitFallbackMessage: Boolean = true,
    ): String? {
        memoryRuntime.episodicMemory.append("USER: $userMessage")
        memoryRuntime.memoryWorker.scheduleIfNeeded()

        val initialState =
            BertBotState(
                lastUserMessage = userMessage,
                memorySummary =
                    memoryRuntime.memoryAssembler
                        .buildContext(
                            maxSemanticEntries = config.maxSemanticContextEntries,
                            maxEpisodicEntries = config.maxEpisodicContextEntries,
                        ).toMutableList(),
            )

        val state =
            try {
                graph.run(initialState)
            } catch (e: MaxTurnsExceededException) {
                if (emitFallbackMessage) {
                    println("Assistant: ${e.fallbackMessage}")
                    println("")
                }
                return null
            }

        val systemPrompt = buildSystemPrompt(config, state)
        val tracingContext = TracingContext(traceId = state.traceId ?: TracingContext().traceId)
        val response =
            assistantResponseSkill
                .invoke(
                    input =
                        SelfCorrectingSkillRequest(
                            systemPrompt = systemPrompt,
                            userPrompt = userMessage,
                        ),
                    tracingContext = tracingContext,
                ).response

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
)

internal object BertBotRuntimeFactory {
    fun create(
        config: KoogAgentConfig = KoogAgentConfig(),
        aiRuntimeConfiguration: AiRuntimeConfiguration = resolveAiRuntimeConfiguration(),
    ): BertBotRuntime? {
        require(aiRuntimeConfiguration.provider.equals(DEFAULT_AI_PROVIDER, ignoreCase = true)) {
            "Unsupported AI provider '${aiRuntimeConfiguration.provider}'. Supported providers: $DEFAULT_AI_PROVIDER."
        }

        val apiKey = aiRuntimeConfiguration.apiKey ?: return null

        val stateStore = FileBertBotStateStore(File("bertbot-state.json"))
        val graph = BertBotApplication.createGraph(stateStore, config)
        val llmGateway = createOpenAiLlmGateway(apiKey, aiRuntimeConfiguration.model)
        val episodicMemory = EpisodicMemory()
        val semanticMemory = SemanticMemory()
        val memoryAssembler = DualMemoryContextAssembler(episodicMemory, semanticMemory)
        val memorySummarizer = SafeMemorySummarizer(primary = LlmMemorySummarizer(llmGateway))
        val memoryWorker =
            MemorySummarizationWorker(
                episodicMemory,
                semanticMemory,
                summarizer = memorySummarizer,
                threshold = config.memorySummarizationThreshold,
                summarizeCount = config.memorySummarizationBatchSize,
            )

        return BertBotRuntime(
            config = config,
            aiRuntimeConfiguration = aiRuntimeConfiguration,
            graph = graph,
            assistantResponseSkill = createAssistantResponseSkill(llmGateway),
            memoryRuntime =
                BertBotMemoryRuntime(
                    episodicMemory = episodicMemory,
                    memoryAssembler = memoryAssembler,
                    memoryWorker = memoryWorker,
                ),
        )
    }
}
