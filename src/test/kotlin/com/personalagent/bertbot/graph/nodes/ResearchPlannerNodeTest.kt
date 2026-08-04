package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchPlannerNodeTest {
    @Test
    fun `research planner creates plan when message has research intent`() {
        val node = ResearchPlannerNode()
        val state = BertBotState(lastUserMessage = "research and compare current runtime options")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertNotNull(updated.researchPlan)
        assertEquals(true, updated.researchPlan?.parallelizable)
        assertTrue(updated.executionSummary.any { it.contains("investigation plan", ignoreCase = true) })
    }

    @Test
    fun `research planner skips plan for routine messages`() {
        val node = ResearchPlannerNode()
        val state = BertBotState(lastUserMessage = "hello there")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertNull(updated.researchPlan)
    }

    @Test
    fun `research planner creates plan for urgent intent even without keywords`() {
        val node = ResearchPlannerNode()
        val state = BertBotState(lastUserMessage = "please help", currentIntent = com.personalagent.bertbot.graph.model.BertBotIntent(priority = BertBotPriority.URGENT))

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertNotNull(updated.researchPlan)
    }
}
