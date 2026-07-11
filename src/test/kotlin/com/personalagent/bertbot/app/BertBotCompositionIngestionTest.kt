package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.IngestionConfig
import com.personalagent.bertbot.config.IngestionPolicyConfig
import com.personalagent.bertbot.llm.LlmGateway
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}

private class FakeGateway : LlmGateway {
    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String = "ok"
}
