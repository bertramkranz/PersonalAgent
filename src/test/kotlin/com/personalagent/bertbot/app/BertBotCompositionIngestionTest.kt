package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.IngestionConfig
import com.personalagent.bertbot.config.IngestionPolicyConfig
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import com.personalagent.bertbot.graph.store.JdbcBertBotStateStore
import com.personalagent.bertbot.ingestion.ApprovalScope
import com.personalagent.bertbot.ingestion.ApprovalUpdateRequest
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.llm.LlmGateway
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BertBotCompositionIngestionTest {
    @Test
    fun `ingestion runtime is disabled by default`() {
        val config = BertBotAgentConfig()
        val memoryRuntime = BertBotRuntimeDependenciesFactory.createMemoryRuntime(config, FakeGateway())

        val ingestionRuntime = BertBotRuntimeDependenciesFactory.createIngestionRuntime(config, memoryRuntime)

        assertNull(ingestionRuntime)
        memoryRuntime.memoryWorker.close()
    }

    @Test
    fun `ingestion runtime can be enabled through config`() {
        val config = BertBotAgentConfig(ingestion = IngestionConfig(policy = IngestionPolicyConfig(enabled = true)))
        val memoryRuntime = BertBotRuntimeDependenciesFactory.createMemoryRuntime(config, FakeGateway())

        val ingestionRuntime = BertBotRuntimeDependenciesFactory.createIngestionRuntime(config, memoryRuntime)

        assertNotNull(ingestionRuntime)
        memoryRuntime.memoryWorker.close()
    }

    @Test
    fun `state store factory uses file backend by default`() {
        val store = BertBotRuntimeDependenciesFactory.createStateStore(PersistenceRuntimeConfiguration())

        assertIs<FileBertBotStateStore>(store)
    }

    @Test
    fun `state store factory supports jdbc backend`() {
        val jdbcUrl = "jdbc:h2:mem:bertbot_factory_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        val store =
            BertBotRuntimeDependenciesFactory.createStateStore(
                PersistenceRuntimeConfiguration(
                    backend = "jdbc",
                    jdbcUrl = jdbcUrl,
                ),
            )

        assertIs<JdbcBertBotStateStore>(store)
    }

    @Test
    fun `memory runtime supports jdbc backend persistence`() {
        val jdbcUrl = "jdbc:h2:mem:bertbot_memory_factory_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        val persistenceConfiguration =
            PersistenceRuntimeConfiguration(
                backend = "jdbc",
                jdbcUrl = jdbcUrl,
                episodicMemoryJdbcTable = "episodic_test_snapshot",
                semanticMemoryJdbcTable = "semantic_test_snapshot",
                profileJdbcTable = "profile_test_snapshot",
            )

        val firstRuntime =
            BertBotRuntimeDependenciesFactory.createMemoryRuntime(
                config = BertBotAgentConfig(),
                llmGateway = FakeGateway(),
                persistenceConfiguration = persistenceConfiguration,
            )
        firstRuntime.episodicMemory.append("USER: persisted event")
        firstRuntime.userProfileStore.updateDisplayName("Bertram")
        firstRuntime.memoryWorker.close()

        val secondRuntime =
            BertBotRuntimeDependenciesFactory.createMemoryRuntime(
                config = BertBotAgentConfig(),
                llmGateway = FakeGateway(),
                persistenceConfiguration = persistenceConfiguration,
            )

        assertTrue(secondRuntime.episodicMemory.entries().any { it.text == "USER: persisted event" })
        assertEquals("Bertram", secondRuntime.userProfileStore.current().displayName)
        secondRuntime.memoryWorker.close()
    }

    @Test
    fun `ingestion runtime supports jdbc backend persistence`() {
        val jdbcUrl = "jdbc:h2:mem:bertbot_ingestion_factory_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        val persistenceConfiguration =
            PersistenceRuntimeConfiguration(
                backend = "jdbc",
                jdbcUrl = jdbcUrl,
                episodicMemoryJdbcTable = "episodic_ingest_snapshot",
                semanticMemoryJdbcTable = "semantic_ingest_snapshot",
                profileJdbcTable = "profile_ingest_snapshot",
                ingestionConsentJdbcTable = "consent_ingest_snapshot",
                ingestionSourceStateJdbcTable = "source_state_ingest_snapshot",
            )
        val config = BertBotAgentConfig(ingestion = IngestionConfig(policy = IngestionPolicyConfig(enabled = true)))

        val firstMemoryRuntime =
            BertBotRuntimeDependenciesFactory.createMemoryRuntime(
                config = config,
                llmGateway = FakeGateway(),
                persistenceConfiguration = persistenceConfiguration,
            )
        val firstIngestionRuntime =
            BertBotRuntimeDependenciesFactory.createIngestionRuntime(
                config = config,
                memoryRuntime = firstMemoryRuntime,
                persistenceConfiguration = persistenceConfiguration,
            )
        assertNotNull(firstIngestionRuntime)

        val source =
            IngestionSource(
                platform = IngestionPlatform.TELEGRAM,
                sourceKind = IngestionSourceKind.CHAT,
                sourceId = "chat-1",
            )
        firstIngestionRuntime.controlPlane.setApproval(
            ApprovalUpdateRequest(
                source = source,
                scope = ApprovalScope.CHAT,
                approved = true,
            ),
        )
        firstMemoryRuntime.memoryWorker.close()

        val secondMemoryRuntime =
            BertBotRuntimeDependenciesFactory.createMemoryRuntime(
                config = config,
                llmGateway = FakeGateway(),
                persistenceConfiguration = persistenceConfiguration,
            )
        val secondIngestionRuntime =
            BertBotRuntimeDependenciesFactory.createIngestionRuntime(
                config = config,
                memoryRuntime = secondMemoryRuntime,
                persistenceConfiguration = persistenceConfiguration,
            )
        assertNotNull(secondIngestionRuntime)

        val approvedSources = secondIngestionRuntime.controlPlane.listApprovedSources()
        assertEquals(1, approvedSources.size)
        assertEquals("chat-1", approvedSources.first().source.sourceId)
        secondMemoryRuntime.memoryWorker.close()
    }
}

private class FakeGateway : LlmGateway {
    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String = "ok"
}
