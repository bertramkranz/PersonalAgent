package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.ConnectorConfig
import com.personalagent.bertbot.config.DiscordIntegrationConfig
import com.personalagent.bertbot.config.IngestionConfig
import com.personalagent.bertbot.config.IngestionPolicyConfig
import com.personalagent.bertbot.config.SlackIntegrationConfig
import com.personalagent.bertbot.config.TelegramIntegrationConfig
import com.personalagent.bertbot.config.WhatsAppIntegrationConfig
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.llm.LlmGateway
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.MemorySummarizationWorker
import com.personalagent.bertbot.memory.SemanticMemory
import com.personalagent.bertbot.memory.UserProfileStore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BertBotConnectorRuntimeFactoryTest {
    @Test
    fun `factory creates all connector adapters when enabled`() {
        val config =
            BertBotAgentConfig(
                ingestion =
                    IngestionConfig(
                        policy = IngestionPolicyConfig(enabled = true),
                        telegram = TelegramIntegrationConfig(connector = ConnectorConfig(enabled = true)),
                        slack = SlackIntegrationConfig(connector = ConnectorConfig(enabled = true, approvalScope = "channel")),
                        whatsapp = WhatsAppIntegrationConfig(connector = ConnectorConfig(enabled = true, approvalScope = "conversation")),
                        discord = DiscordIntegrationConfig(connector = ConnectorConfig(enabled = true, approvalScope = "channel")),
                    ),
            )

        val runtime = createRuntimeForConnectorFactory(config)

        val connectors = BertBotConnectorRuntimeFactory.create(config, runtime)

        assertNotNull(connectors.telegram)
        assertNotNull(connectors.slack)
        assertNotNull(connectors.whatsapp)
        assertNotNull(connectors.discord)
        runtime.close()
    }

    @Test
    fun `factory returns empty runtime when ingestion policy disabled`() {
        val config = BertBotAgentConfig()
        val runtime = createRuntimeForConnectorFactory(config)

        val connectors = BertBotConnectorRuntimeFactory.create(config, runtime)

        assertNull(connectors.telegram)
        assertNull(connectors.slack)
        assertNull(connectors.whatsapp)
        assertNull(connectors.discord)
        runtime.close()
    }
}

private fun createRuntimeForConnectorFactory(config: BertBotAgentConfig): BertBotRuntime {
    val episodic = EpisodicMemory()
    val semantic = SemanticMemory()
    val memoryRuntime =
        BertBotMemoryRuntime(
            episodicMemory = episodic,
            memoryAssembler = DualMemoryContextAssembler(episodic, semantic),
            memoryWorker = MemorySummarizationWorker(episodic, semantic, threshold = 10, summarizeCount = 5),
            userProfileStore = UserProfileStore(),
        )

    return BertBotRuntime(
        config = config,
        aiRuntimeConfiguration = AiRuntimeConfiguration(provider = "openai", model = "gpt-4o-mini", apiKey = "test-key"),
        stateStore = NoopStateStore(),
        graph = BertBotGraphRunner(definition = BertBotGraphDefinition(entryNodeId = "none", nodes = emptyList(), edges = emptyList()), stateStore = NoopStateStore()),
        assistantResponseSkill = createAssistantResponseSkill(FakeGatewayForConnectorFactory()),
        memoryRuntime = memoryRuntime,
        ingestionRuntime = null,
    )
}

private class FakeGatewayForConnectorFactory : LlmGateway {
    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String = "{\"response\":\"ok\"}"
}

private class NoopStateStore : BertBotStateStore {
    override fun load() = com.personalagent.bertbot.graph.model.BertBotState()

    override fun save(state: com.personalagent.bertbot.graph.model.BertBotState) = Unit
}
