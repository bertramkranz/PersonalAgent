package com.personalagent.bertbot.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCapabilityRegistryTest {
    @Test
    fun `registry exposes default models and positive token estimates`() {
        val registry = ModelCapabilityRegistry()

        assertNotNull(registry.modelById("gpt-5.6-luna"))
        assertTrue(registry.estimateTokens("hello world") > 0)
    }

    @Test
    fun `registry estimates cost based on model pricing`() {
        val registry = ModelCapabilityRegistry()

        val estimated = registry.estimateCost("gpt-5.6-luna", inputTokens = 1000, outputTokens = 500)

        assertTrue(estimated > 0.0)
        // 1000 * $0.20/M + 500 * $1.20/M = $0.0002 + $0.0006 = $0.0008
        assertEquals(0.0008, estimated, absoluteTolerance = 0.000001)
    }
}
