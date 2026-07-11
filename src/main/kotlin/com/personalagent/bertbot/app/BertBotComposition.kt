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
import com.personalagent.bertbot.graph.store.JdbcBertBotStateStore
import com.personalagent.bertbot.ingestion.FileConsentStore
import com.personalagent.bertbot.ingestion.FileSourceStateStore
import com.personalagent.bertbot.ingestion.IngestionService
import com.personalagent.bertbot.ingestion.JdbcConsentStore
import com.personalagent.bertbot.ingestion.JdbcSourceStateStore
import com.personalagent.bertbot.ingestion.ReferenceOnlyMediaPolicy
import com.personalagent.bertbot.ingestion.connectors.SlackChatBridge
import com.personalagent.bertbot.ingestion.connectors.SlackConnectorAdapter
import com.personalagent.bertbot.ingestion.connectors.TelegramChatBridge
import com.personalagent.bertbot.ingestion.connectors.TelegramConnectorAdapter
import com.personalagent.bertbot.ingestion.connectors.WhatsAppChatBridge
import com.personalagent.bertbot.ingestion.connectors.WhatsAppConnectorAdapter
import com.personalagent.bertbot.llm.LlmGateway
import com.personalagent.bertbot.memory.BertBotMemory
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.JdbcBertBotMemoryStore
import com.personalagent.bertbot.memory.JdbcUserProfileStore
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
    private val jdbcBackends = setOf("jdbc", "postgres", "postgresql")

    fun createStateStore(
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotStateStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        return when (normalizedBackend) {
            "file" -> FileBertBotStateStore(File(persistenceConfiguration.stateFilePath))
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
                    }
                JdbcBertBotStateStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.jdbcTable,
                )
            }
            else -> FileBertBotStateStore(File(persistenceConfiguration.stateFilePath))
        }
    }

    fun createMemoryRuntime(
        config: BertBotAgentConfig,
        llmGateway: LlmGateway,
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotMemoryRuntime {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        val episodicMemory = createEpisodicMemory(normalizedBackend, persistenceConfiguration)
        val semanticMemory = createSemanticMemory(normalizedBackend, persistenceConfiguration)
        val memoryAssembler = DualMemoryContextAssembler(episodicMemory, semanticMemory)
        val memorySummarizer = SafeMemorySummarizer(primary = LlmMemorySummarizer(llmGateway))
        val userProfileStore = createUserProfileStore(normalizedBackend, persistenceConfiguration)
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
            semanticMemory = semanticMemory,
            memoryAssembler = memoryAssembler,
            memoryWorker = memoryWorker,
            userProfileStore = userProfileStore,
        )
    }

    fun createIngestionRuntime(
        config: BertBotAgentConfig,
        memoryRuntime: BertBotMemoryRuntime,
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotIngestionRuntime? {
        if (!config.ingestion.policy.enabled) {
            return null
        }

        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        val consentStore = createConsentStore(normalizedBackend, persistenceConfiguration)
        val sourceStateStore = createSourceStateStore(normalizedBackend, persistenceConfiguration)

        val service =
            IngestionService(
                consentStore = consentStore,
                sourceStateStore = sourceStateStore,
                episodicMemory = memoryRuntime.episodicMemory,
                semanticSummarizationTrigger = { memoryRuntime.memoryWorker.scheduleIfNeeded() },
                userProfileStore = memoryRuntime.userProfileStore,
                mediaPolicy = ReferenceOnlyMediaPolicy(),
            )

        return BertBotIngestionRuntime(controlPlane = service)
    }

    private fun createEpisodicMemory(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ): EpisodicMemory {
        if (normalizedBackend in jdbcBackends) {
            return EpisodicMemory(
                JdbcBertBotMemoryStore(
                    jdbcUrl = requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.episodicMemoryJdbcTable,
                ),
            )
        }

        return EpisodicMemory(BertBotMemory(File(persistenceConfiguration.episodicMemoryFilePath)))
    }

    private fun createSemanticMemory(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ): SemanticMemory {
        if (normalizedBackend in jdbcBackends) {
            return SemanticMemory(
                JdbcBertBotMemoryStore(
                    jdbcUrl = requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.semanticMemoryJdbcTable,
                ),
            )
        }

        return SemanticMemory(BertBotMemory(File(persistenceConfiguration.semanticMemoryFilePath)))
    }

    private fun createUserProfileStore(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ): UserProfileStore {
        if (normalizedBackend in jdbcBackends) {
            return UserProfileStore(
                JdbcUserProfileStore(
                    jdbcUrl = requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.profileJdbcTable,
                ),
            )
        }

        return UserProfileStore(File(persistenceConfiguration.profileFilePath))
    }

    private fun createConsentStore(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ) =
        if (normalizedBackend in jdbcBackends) {
            JdbcConsentStore(
                jdbcUrl = requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                username = persistenceConfiguration.jdbcUser,
                password = persistenceConfiguration.jdbcPassword,
                tableName = persistenceConfiguration.ingestionConsentJdbcTable,
            )
        } else {
            FileConsentStore(File(persistenceConfiguration.ingestionConsentFilePath))
        }

    private fun createSourceStateStore(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ) =
        if (normalizedBackend in jdbcBackends) {
            JdbcSourceStateStore(
                jdbcUrl = requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                username = persistenceConfiguration.jdbcUser,
                password = persistenceConfiguration.jdbcPassword,
                tableName = persistenceConfiguration.ingestionSourceStateJdbcTable,
            )
        } else {
            FileSourceStateStore(File(persistenceConfiguration.ingestionSourceStateFilePath))
        }

    private fun requireJdbcUrl(
        persistenceConfiguration: PersistenceRuntimeConfiguration,
        normalizedBackend: String,
    ): String =
        requireNotNull(persistenceConfiguration.jdbcUrl) {
            "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
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
