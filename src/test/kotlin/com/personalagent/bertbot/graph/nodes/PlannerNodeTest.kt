package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerNodeTest {
    @Test
    fun `planner treats configured non actionable phrase as no follow up`() {
        val node = PlannerNode(nonActionableMessages = setOf("yo", "hello"))
        val state = BertBotState(lastUserMessage = "yo!!!")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.pendingTasks.isEmpty())
        assertEquals(BertBotPriority.NONE, updated.currentIntent?.priority)
        assertEquals(false, updated.currentIntent?.actionable)
        assertTrue(updated.executionSummary.contains("No follow-up required"))
    }

    @Test
    fun `planner creates routine follow up for actionable message`() {
        val node = PlannerNode(nonActionableMessages = setOf("yo", "hello"))
        val state = BertBotState(lastUserMessage = "please review architecture")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals(listOf("Routine follow-up"), updated.pendingTasks)
        assertEquals(BertBotPriority.ROUTINE, updated.currentIntent?.priority)
        assertEquals(true, updated.currentIntent?.actionable)
    }

    @Test
    fun `planner marks urgent actionable message with typed priority`() {
        val node = PlannerNode(nonActionableMessages = setOf("yo", "hello"))
        val state = BertBotState(lastUserMessage = "priority review needed today")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals(listOf("Urgent follow-up required"), updated.pendingTasks)
        assertEquals(BertBotPriority.URGENT, updated.currentIntent?.priority)
        assertEquals(true, updated.currentIntent?.actionable)
    }
}
