package com.personalagent.bertbot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KoogAgentConfigTest {
    @Test
    fun `memory tuning defaults remain stable`() {
        val config = KoogAgentConfig()

        assertEquals(5, config.maxSemanticContextEntries)
        assertEquals(10, config.maxEpisodicContextEntries)
        assertEquals(15, config.memorySummarizationThreshold)
        assertEquals(10, config.memorySummarizationBatchSize)
    }

    @Test
    fun `memory tuning values are overridable`() {
        val config =
            KoogAgentConfig(
                maxSemanticContextEntries = 3,
                maxEpisodicContextEntries = 6,
                memorySummarizationThreshold = 12,
                memorySummarizationBatchSize = 8,
            )

        assertEquals(3, config.maxSemanticContextEntries)
        assertEquals(6, config.maxEpisodicContextEntries)
        assertEquals(12, config.memorySummarizationThreshold)
        assertEquals(8, config.memorySummarizationBatchSize)
    }

    @Test
    fun `memory tuning values must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(maxSemanticContextEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(maxEpisodicContextEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(memorySummarizationThreshold = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(memorySummarizationBatchSize = 0)
        }
    }

    @Test
    fun `memory summarization batch must not exceed threshold`() {
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(memorySummarizationThreshold = 5, memorySummarizationBatchSize = 6)
        }
    }

    @Test
    fun `memory tuning values must respect upper bounds`() {
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(maxSemanticContextEntries = KoogAgentConfig.MAX_SEMANTIC_CONTEXT_ENTRIES + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(maxEpisodicContextEntries = KoogAgentConfig.MAX_EPISODIC_CONTEXT_ENTRIES + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(
                memorySummarizationThreshold = KoogAgentConfig.MAX_MEMORY_SUMMARIZATION_THRESHOLD + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KoogAgentConfig(
                memorySummarizationBatchSize = KoogAgentConfig.MAX_MEMORY_SUMMARIZATION_BATCH_SIZE + 1,
            )
        }
    }
}
