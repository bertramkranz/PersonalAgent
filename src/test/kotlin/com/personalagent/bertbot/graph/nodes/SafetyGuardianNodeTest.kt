package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafetyGuardianNodeTest {
    @Test
    fun `safety guardian requires approval for high risk language`() {
        val node = SafetyGuardianNode()
        val state = BertBotState(lastUserMessage = "delete this and checkout now", pendingTasks = mutableListOf("Urgent follow-up"))

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.requiresUserApproval)
        assertEquals("requires_user_approval", updated.delegationDecision?.reason)
        assertTrue(updated.safetyCheckResults.last().requiresApproval)
    }

    @Test
    fun `safety guardian approves low risk language`() {
        val node = SafetyGuardianNode()
        val state = BertBotState(lastUserMessage = "review architecture proposal", pendingTasks = mutableListOf("Routine follow-up"))

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals(false, updated.requiresUserApproval)
        assertTrue(updated.safetyCheckResults.last().passed)
    }
}
