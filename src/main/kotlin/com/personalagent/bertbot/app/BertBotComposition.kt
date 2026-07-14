package com.personalagent.bertbot.app

import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.nodes.DelegationNode
import com.personalagent.bertbot.graph.nodes.ExecutorNode
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.nodes.PlannerNode
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphEdge
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotRollbackService
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.graph.runtime.CompensatingRollbackService
import com.personalagent.bertbot.graph.runtime.DelegationToExecutorStateValidator
import com.personalagent.bertbot.graph.runtime.ExternalChatReplyCompensator
import com.personalagent.bertbot.graph.runtime.StateEventStore
import com.personalagent.bertbot.graph.runtime.StateHandoffValidator
import com.personalagent.bertbot.graph.runtime.StateOnlyRollbackService
import com.personalagent.bertbot.graph.runtime.StateReplayService
import com.personalagent.bertbot.graph.runtime.ToolCompensator
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.graph.store.FileBertBotCheckpointStore
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import com.personalagent.bertbot.graph.store.FileStateEventStore
import com.personalagent.bertbot.graph.store.JdbcBertBotCheckpointStore
import com.personalagent.bertbot.graph.store.JdbcBertBotStateStore
import com.personalagent.bertbot.graph.store.JdbcStateEventStore
import com.personalagent.bertbot.ingestion.FileConsentStore
import com.personalagent.bertbot.ingestion.FileSourceStateStore
import com.personalagent.bertbot.ingestion.IngestionService
import com.personalagent.bertbot.ingestion.JdbcConsentStore
import com.personalagent.bertbot.ingestion.JdbcSourceStateStore
import com.personalagent.bertbot.ingestion.connectors.DiscordChatBridge
import com.personalagent.bertbot.ingestion.connectors.DiscordConnectorAdapter
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
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import com.personalagent.bertbot.serialization.KotlinxAgentJsonCodec
import java.io.File

internal object BertBotGraphFactory {
    fun create(
        stateStore: BertBotStateStore,
        config: BertBotAgentConfig,
        checkpointStore: BertBotCheckpointStore? = null,
        enableAutomaticCheckpointing: Boolean = false,
        eventSourcingConfiguration: BertBotGraphRunner.EventSourcingConfiguration = BertBotGraphRunner.EventSourcingConfiguration(),
    ): BertBotGraphRunner =
        BertBotGraphRunner(
            definition = createDefinition(config),
            stateStore = stateStore,
            handoffValidators = createHandoffValidators(),
            checkpointStore = checkpointStore,
            enableAutomaticCheckpointing = enableAutomaticCheckpointing,
            eventSourcing = eventSourcingConfiguration,
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
    fun createStateStore(
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotStateStore = BertBotStateStoreFactory.create(persistenceConfiguration)

    fun createJsonCodec(
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): AgentJsonCodec {
        return when (persistenceConfiguration.jsonCodec.lowercase()) {
            "kotlinx", "kotlinx-serialization", "koog" -> KotlinxAgentJsonCodec()
            else -> GsonAgentJsonCodec()
        }
    }

    fun createMemoryRuntime(
        config: BertBotAgentConfig,
        llmGateway: LlmGateway,
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotMemoryRuntime = BertBotMemoryRuntimeFactory.create(config, llmGateway, persistenceConfiguration)

    fun createIngestionRuntime(
        config: BertBotAgentConfig,
        memoryRuntime: BertBotMemoryRuntime,
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotIngestionRuntime? = BertBotIngestionRuntimeFactory.create(config, memoryRuntime, persistenceConfiguration)

    fun createResearchRuntime(
        config: BertBotAgentConfig,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
        workspaceRoot: File,
        enablePeriodicScheduler: Boolean,
        llmGateway: LlmGateway? = null,
    ): BertBotResearchRuntime? =
        BertBotResearchRuntimeFactory.create(
            config = config,
            persistenceConfiguration = persistenceConfiguration,
            workspaceRoot = workspaceRoot,
            enablePeriodicScheduler = enablePeriodicScheduler,
            llmGateway = llmGateway,
        )

    fun createCheckpointStore(
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): BertBotCheckpointStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        val codec = createJsonCodec(persistenceConfiguration)
        return when {
            BertBotJdbcBackend.isJdbcBackend(normalizedBackend) ->
                JdbcBertBotCheckpointStore(
                    jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.checkpointJdbcTable,
                    codec = codec,
                )
            else -> FileBertBotCheckpointStore(File(persistenceConfiguration.checkpointFilePath), codec = codec)
        }
    }

    fun createRollbackService(
        stateStore: BertBotStateStore,
        checkpointStore: BertBotCheckpointStore,
        stateEventStore: StateEventStore? = null,
        compensators: List<ToolCompensator> = createCompensators(),
    ): BertBotRollbackService =
        CompensatingRollbackService(
            stateRollbackService = StateOnlyRollbackService(stateStore = stateStore, checkpointStore = checkpointStore),
            checkpointStore = checkpointStore,
            stateEventStore = stateEventStore,
            compensators = compensators,
        )

    fun createStateEventStore(
        persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    ): StateEventStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        val codec = createJsonCodec(persistenceConfiguration)
        return when {
            BertBotJdbcBackend.isJdbcBackend(normalizedBackend) ->
                JdbcStateEventStore(
                    jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.stateEventJdbcTable,
                    codec = codec,
                )
            else -> FileStateEventStore(File(persistenceConfiguration.stateEventFilePath), codec = codec)
        }
    }

    fun createCompensators(): List<ToolCompensator> =
        listOf(
            ExternalChatReplyCompensator("telegram"),
            ExternalChatReplyCompensator("slack"),
            ExternalChatReplyCompensator("whatsapp"),
            ExternalChatReplyCompensator("discord"),
        )

    fun createStateReplayService(
        checkpointStore: BertBotCheckpointStore,
        stateEventStore: StateEventStore,
    ): StateReplayService = StateReplayService(checkpointStore = checkpointStore, eventStore = stateEventStore)
}

private object BertBotJdbcBackend {
    private val jdbcBackends = setOf("jdbc", "postgres", "postgresql")

    fun isJdbcBackend(backend: String): Boolean = backend in jdbcBackends

    fun requireJdbcUrl(
        persistenceConfiguration: PersistenceRuntimeConfiguration,
        normalizedBackend: String,
    ): String =
        requireNotNull(persistenceConfiguration.jdbcUrl) {
            "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
        }
}

private object BertBotStateStoreFactory {
    fun create(persistenceConfiguration: PersistenceRuntimeConfiguration): BertBotStateStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        val codec = BertBotRuntimeDependenciesFactory.createJsonCodec(persistenceConfiguration)
        return when (normalizedBackend) {
            "file" -> FileBertBotStateStore(File(persistenceConfiguration.stateFilePath), codec = codec)
            "jdbc", "postgres", "postgresql" ->
                JdbcBertBotStateStore(
                    jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.jdbcTable,
                    codec = codec,
                )
            else -> FileBertBotStateStore(File(persistenceConfiguration.stateFilePath), codec = codec)
        }
    }
}

private object BertBotMemoryRuntimeFactory {
    fun create(
        config: BertBotAgentConfig,
        llmGateway: LlmGateway,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
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

    private fun createEpisodicMemory(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ): EpisodicMemory {
        if (BertBotJdbcBackend.isJdbcBackend(normalizedBackend)) {
            return EpisodicMemory(
                JdbcBertBotMemoryStore(
                    jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
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
        if (BertBotJdbcBackend.isJdbcBackend(normalizedBackend)) {
            return SemanticMemory(
                JdbcBertBotMemoryStore(
                    jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
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
        if (BertBotJdbcBackend.isJdbcBackend(normalizedBackend)) {
            return UserProfileStore(
                JdbcUserProfileStore(
                    jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.profileJdbcTable,
                ),
            )
        }

        return UserProfileStore(File(persistenceConfiguration.profileFilePath))
    }
}

private object BertBotIngestionRuntimeFactory {
    fun create(
        config: BertBotAgentConfig,
        memoryRuntime: BertBotMemoryRuntime,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
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
                requireApproval = config.ingestion.policy.requireApproval,
            )

        return BertBotIngestionRuntime(controlPlane = service)
    }

    private fun createConsentStore(
        normalizedBackend: String,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ) =
        if (BertBotJdbcBackend.isJdbcBackend(normalizedBackend)) {
            JdbcConsentStore(
                jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
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
        if (BertBotJdbcBackend.isJdbcBackend(normalizedBackend)) {
            JdbcSourceStateStore(
                jdbcUrl = BertBotJdbcBackend.requireJdbcUrl(persistenceConfiguration, normalizedBackend),
                username = persistenceConfiguration.jdbcUser,
                password = persistenceConfiguration.jdbcPassword,
                tableName = persistenceConfiguration.ingestionSourceStateJdbcTable,
            )
        } else {
            FileSourceStateStore(File(persistenceConfiguration.ingestionSourceStateFilePath))
        }
}

private object BertBotResearchRuntimeFactory {
    fun create(
        config: BertBotAgentConfig,
        persistenceConfiguration: PersistenceRuntimeConfiguration,
        workspaceRoot: File,
        enablePeriodicScheduler: Boolean,
        llmGateway: LlmGateway? = null,
    ): BertBotResearchRuntime? {
        if (!config.research.enabled) {
            return null
        }

        val store = FileImprovementRecommendationStore(File(persistenceConfiguration.researchRecommendationsFilePath))
        val service = ContinuousImprovementResearchService(config = config, workspaceRoot = workspaceRoot, store = store, llmGateway = llmGateway)
        val scheduler =
            if (enablePeriodicScheduler && config.research.periodicEnabled) {
                ContinuousImprovementResearchScheduler(service, intervalSeconds = config.research.periodicIntervalSeconds)
            } else {
                null
            }

        return BertBotResearchRuntime(service = service, scheduler = scheduler)
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
    val discord: DiscordConnectorAdapter? = null,
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
        val profileSummary = buildProfileSummary(knownProfile)
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

internal fun buildProfileSummary(profile: UserProfile): List<String> {
    val summary = mutableListOf<String>()
    profile.displayName?.let { name -> summary += "Known user name: $name" }
    if (profile.preferredBrands.isNotEmpty()) {
        summary += "Preferred brands: ${profile.preferredBrands.sorted().joinToString(", ")}"
    }
    if (profile.preferredSizes.isNotEmpty()) {
        summary += "Preferred sizes: ${profile.preferredSizes.sorted().joinToString(", ")}"
    }
    if (profile.preferredStores.isNotEmpty()) {
        summary += "Preferred stores: ${profile.preferredStores.sorted().joinToString(", ")}"
    }
    profile.budgetLimitCents?.let { cents -> summary += "Shopping budget limit: $cents¢" }
    if (profile.shoppingNotes.isNotEmpty()) {
        summary += "Shopping notes: ${profile.shoppingNotes.sorted().joinToString("; ")}"
    }
    return summary
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
        val discord = if (config.ingestion.discord.connector.enabled) DiscordConnectorAdapter(DiscordChatBridge(responder)) else null
        return BertBotConnectorRuntime(telegram = telegram, slack = slack, whatsapp = whatsapp, discord = discord)
    }
}
