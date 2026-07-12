package com.personalagent.bertbot.app

import com.personalagent.bertbot.llm.LlmGateway
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class KoogMemoryJdbcAdaptersTest {
    @Test
    fun `koog memory integration records turns into jdbc-backed memory runtime`() =
        runBlocking {
            val jdbcUrl =
                "jdbc:h2:mem:koog_memory_jdbc_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            val persistenceConfiguration =
                PersistenceRuntimeConfiguration(
                    backend = "jdbc",
                    jdbcUrl = jdbcUrl,
                )

            val memoryRuntime =
                BertBotRuntimeDependenciesFactory.createMemoryRuntime(
                    config = com.personalagent.bertbot.config.BertBotAgentConfig(),
                    llmGateway = NoopLlmGateway(),
                    persistenceConfiguration = persistenceConfiguration,
                )

            val integration =
                KoogRuntimeIntegrationFactory.createMemory(
                    configuration = KoogFeatureRuntimeConfiguration(),
                    memoryRuntime = memoryRuntime,
                )

            integration.recordTurn(
                scopeKey = "external|telegram|chat|workspace|123|root",
                userMessage = "What did I ask before?",
                assistantResponse = "You asked about memory persistence.",
                traceId = "trace-1",
            )

            val promptContext = integration.buildPromptContext("external|telegram|chat|workspace|123|root", "memory persistence")

            assertTrue(promptContext.contains("Recent conversation history:"))
            assertTrue(promptContext.contains("Long-term memory candidates:"))
            assertTrue(promptContext.contains("USER:"))
            assertTrue(promptContext.contains("ASSISTANT:"))
        }
}

private class NoopLlmGateway : LlmGateway {
    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String = "ok"
}
