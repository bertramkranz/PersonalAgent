package com.personalagent.bertbot.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCapabilityRegistryTest {
    @Test
    fun `registry exposes default models and positive token estimates`() {
        val registry = ModelCapabilityRegistry()

        assertNotNull(registry.modelById("gpt-4o-mini"))
        assertTrue(registry.estimateTokens("hello world") > 0)
    }

    @Test
    fun `registry estimates cost based on model pricing`() {
        val registry = ModelCapabilityRegistry()

        val estimated = registry.estimateCost("gpt-4o-mini", inputTokens = 1000, outputTokens = 500)

        assertTrue(estimated > 0.0)
        assertEquals(0.00045, estimated, absoluteTolerance = 0.000001)
    }
}
