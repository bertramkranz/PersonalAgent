package com.personalagent.bertbot.app

import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.nodes.DelegationNode
import com.personalagent.bertbot.graph.nodes.ExecutorNode
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.nodes.PlannerNode
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphEdge
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.graph.runtime.DelegationToExecutorStateValidator
import com.personalagent.bertbot.graph.runtime.StateHandoffValidator
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import com.personalagent.bertbot.ingestion.FileConsentStore
import com.personalagent.bertbot.ingestion.FileSourceStateStore
import com.personalagent.bertbot.ingestion.IngestionService
import com.personalagent.bertbot.ingestion.ReferenceOnlyMediaPolicy
import com.personalagent.bertbot.ingestion.connectors.SlackChatBridge
import com.personalagent.bertbot.ingestion.connectors.SlackConnectorAdapter
import com.personalagent.bertbot.ingestion.connectors.TelegramChatBridge
import com.personalagent.bertbot.ingestion.connectors.TelegramConnectorAdapter
import com.personalagent.bertbot.ingestion.connectors.WhatsAppChatBridge
import com.personalagent.bertbot.ingestion.connectors.WhatsAppConnectorAdapter
import com.personalagent.bertbot.llm.LlmGateway
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.LlmMemorySummarizer
import com.personalagent.bertbot.memory.MemorySummarizationWorker
import com.personalagent.bertbot.memory.SafeMemorySummarizer
import com.personalagent.bertbot.memory.SemanticMemory
import com.personalagent.bertbot.memory.UserProfile
import com.personalagent.bertbot.memory.UserProfileStore
import java.io.File

internal object BertBotGraphFactory {
    fun create(
        stateStore: BertBotStateStore,
        config: BertBotAgentConfig,
    ): BertBotGraphRunner =
        BertBotGraphRunner(
            definition = createDefinition(config),
            stateStore = stateStore,
            handoffValidators = createHandoffValidators(),
        )

    private fun createDefinition(config: BertBotAgentConfig): BertBotGraphDefinition {
        val registry = SubAgentRegistry(config)
        return BertBotGraphDefinition(
            entryNodeId = NodeIds.CAPTURE,
            nodes =
                listOf(
                    MessageCaptureNode(),
                    PlannerNode(config.nonActionableMessages),
                    DelegationNode(registry),
                    ExecutorNode(),
                ),
            edges =
                listOf(
                    BertBotGraphEdge(NodeIds.CAPTURE, NodeIds.PLANNER) { true },
                    BertBotGraphEdge(NodeIds.PLANNER, NodeIds.DELEGATION) { it.pendingTasks.isNotEmpty() },
                    BertBotGraphEdge(NodeIds.PLANNER, NodeIds.EXECUTOR) { it.pendingTasks.isEmpty() },
                    BertBotGraphEdge(NodeIds.DELEGATION, NodeIds.EXECUTOR) { true },
                ),
        )
    }

    private fun createHandoffValidators(): List<StateHandoffValidator<BertBotState>> =
        listOf(
            StateHandoffValidator(
                fromNodeId = NodeIds.DELEGATION,
                toNodeId = NodeIds.EXECUTOR,
                validator = DelegationToExecutorStateValidator(),
            ),
        )
}

internal object BertBotRuntimeDependenciesFactory {
    fun createStateStore(): BertBotStateStore = FileBertBotStateStore(File("bertbot-state.json"))

    fun createMemoryRuntime(
        config: BertBotAgentConfig,
        llmGateway: LlmGateway,
    ): BertBotMemoryRuntime {
        val episodicMemory = EpisodicMemory()
        val semanticMemory = SemanticMemory()
        val memoryAssembler = DualMemoryContextAssembler(episodicMemory, semanticMemory)
        val memorySummarizer = SafeMemorySummarizer(primary = LlmMemorySummarizer(llmGateway))
        val userProfileStore = UserProfileStore()
        val memoryWorker =
            MemorySummarizationWorker(
                episodicMemory,
                semanticMemory,
                summarizer = memorySummarizer,
                threshold = config.memorySummarizationThreshold,
                summarizeCount = config.memorySummarizationBatchSize,
            )

        return BertBotMemoryRuntime(
            episodicMemory = episodicMemory,
            memoryAssembler = memoryAssembler,
            memoryWorker = memoryWorker,
            userProfileStore = userProfileStore,
        )
    }

    fun createIngestionRuntime(
        config: BertBotAgentConfig,
        memoryRuntime: BertBotMemoryRuntime,
    ): BertBotIngestionRuntime? {
        if (!config.ingestion.policy.enabled) {
            return null
        }

        val service =
            IngestionService(
                consentStore = FileConsentStore(),
                sourceStateStore = FileSourceStateStore(),
                episodicMemory = memoryRuntime.episodicMemory,
                semanticSummarizationTrigger = { memoryRuntime.memoryWorker.scheduleIfNeeded() },
                userProfileStore = memoryRuntime.userProfileStore,
                mediaPolicy = ReferenceOnlyMediaPolicy(),
            )

        return BertBotIngestionRuntime(controlPlane = service)
    }
}

internal data class BertBotRequestContext(
    val initialState: BertBotState,
    val knownProfile: UserProfile,
    val requestTraceId: String,
)

internal data class BertBotConnectorRuntime(
    val telegram: TelegramConnectorAdapter? = null,
    val slack: SlackConnectorAdapter? = null,
    val whatsapp: WhatsAppConnectorAdapter? = null,
)

internal class BertBotRequestContextBuilder(
    private val config: BertBotAgentConfig,
    private val memoryRuntime: BertBotMemoryRuntime,
) {
    fun build(
        userMessage: String,
        traceCorrelationId: String? = null,
    ): BertBotRequestContext {
        extractDisplayNameFromMessage(userMessage)?.let { extractedName ->
            memoryRuntime.userProfileStore.updateDisplayName(extractedName)
        }

        memoryRuntime.episodicMemory.append("USER: $userMessage")
        memoryRuntime.memoryWorker.scheduleIfNeeded()

        val knownProfile = memoryRuntime.userProfileStore.current()
        val profileSummary = knownProfile.displayName?.let { name -> listOf("Known user name: $name") } ?: emptyList()
        val requestTraceId = traceCorrelationId?.let { "mcp-$it-${TracingContext().traceId}" } ?: TracingContext().traceId
        val initialState =
            BertBotState(
                traceId = requestTraceId,
                lastUserMessage = userMessage,
                memorySummary =
                    memoryRuntime.memoryAssembler
                        .buildContext(
                            maxSemanticEntries = config.maxSemanticContextEntries,
                            maxEpisodicEntries = config.maxEpisodicContextEntries,
                        ).toMutableList(),
                profileSummary = profileSummary.toMutableList(),
            )

        return BertBotRequestContext(
            initialState = initialState,
            knownProfile = knownProfile,
            requestTraceId = requestTraceId,
        )
    }
}

internal object BertBotConnectorRuntimeFactory {
    fun create(
        config: BertBotAgentConfig,
        runtime: BertBotRuntime,
    ): BertBotConnectorRuntime {
        if (!config.ingestion.policy.enabled) {
            return BertBotConnectorRuntime()
        }

        val responder = runtime.externalChatResponder()
        val telegram = if (config.ingestion.telegram.connector.enabled) TelegramConnectorAdapter(TelegramChatBridge(responder)) else null
        val slack = if (config.ingestion.slack.connector.enabled) SlackConnectorAdapter(SlackChatBridge(responder)) else null
        val whatsapp = if (config.ingestion.whatsapp.connector.enabled) WhatsAppConnectorAdapter(WhatsAppChatBridge(responder)) else null
        return BertBotConnectorRuntime(telegram = telegram, slack = slack, whatsapp = whatsapp)
    }
}
