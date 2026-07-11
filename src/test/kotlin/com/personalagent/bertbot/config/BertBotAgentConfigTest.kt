package com.personalagent.bertbot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BertBotAgentConfigTest {
    @Test
    fun `memory tuning defaults remain stable`() {
        val config = BertBotAgentConfig()

        assertEquals(5, config.maxSemanticContextEntries)
        assertEquals(10, config.maxEpisodicContextEntries)
        assertEquals(15, config.memorySummarizationThreshold)
        assertEquals(10, config.memorySummarizationBatchSize)
        assertEquals(false, config.ingestion.policy.enabled)
        assertEquals(true, config.ingestion.policy.storeImageReferencesOnly)
        assertEquals(setOf("hi", "hello", "hey", "thanks", "thank you", "ok", "okay"), config.nonActionableMessages)
    }

    @Test
    fun `memory tuning values are overridable`() {
        val config =
            BertBotAgentConfig(
                maxSemanticContextEntries = 3,
                maxEpisodicContextEntries = 6,
                memorySummarizationThreshold = 12,
                memorySummarizationBatchSize = 8,
                nonActionableMessages = setOf("yo", "sup"),
            )

        assertEquals(3, config.maxSemanticContextEntries)
        assertEquals(6, config.maxEpisodicContextEntries)
        assertEquals(12, config.memorySummarizationThreshold)
        assertEquals(8, config.memorySummarizationBatchSize)
        assertEquals(setOf("yo", "sup"), config.nonActionableMessages)
    }

    @Test
    fun `memory tuning values must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(maxSemanticContextEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(maxEpisodicContextEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(memorySummarizationThreshold = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(memorySummarizationBatchSize = 0)
        }
    }

    @Test
    fun `non actionable message list must not contain blanks`() {
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(nonActionableMessages = setOf("hello", " "))
        }
    }

    @Test
    fun `memory summarization batch must not exceed threshold`() {
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(memorySummarizationThreshold = 5, memorySummarizationBatchSize = 6)
        }
    }

    @Test
    fun `memory tuning values must respect upper bounds`() {
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(maxSemanticContextEntries = BertBotAgentConfig.MAX_SEMANTIC_CONTEXT_ENTRIES + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(maxEpisodicContextEntries = BertBotAgentConfig.MAX_EPISODIC_CONTEXT_ENTRIES + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(
                memorySummarizationThreshold = BertBotAgentConfig.MAX_MEMORY_SUMMARIZATION_THRESHOLD + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(
                memorySummarizationBatchSize = BertBotAgentConfig.MAX_MEMORY_SUMMARIZATION_BATCH_SIZE + 1,
            )
        }
    }

    @Test
    fun `ingestion connector approval scope must be supported`() {
        assertFailsWith<IllegalArgumentException> {
            BertBotAgentConfig(
                ingestion =
                    IngestionConfig(
                        telegram =
                            TelegramIntegrationConfig(
                                connector = ConnectorConfig(approvalScope = "workspace"),
                            ),
                    ),
            )
        }
    }
}
