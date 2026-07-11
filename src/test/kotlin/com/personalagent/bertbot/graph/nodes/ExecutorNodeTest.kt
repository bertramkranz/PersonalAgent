package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotDelegationDecision
import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutorNodeTest {
    @Test
    fun `executor resolves delegated workflow from explicit decision`() {
        val node = ExecutorNode()
        val state =
            BertBotState(
                currentIntent =
                    BertBotIntent(
                        summary = "Routine follow-up",
                        actionable = true,
                        priority = BertBotPriority.ROUTINE,
                    ),
                delegationDecision =
                    BertBotDelegationDecision(
                        attempted = true,
                        selectedSubAgentId = "architect",
                        reason = "matched_sub_agent",
                    ),
                delegationPlan = mutableListOf("Delegate to Architect"),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.intentResolved)
        assertTrue(updated.executionSummary.contains("Executed delegated workflow"))
    }

    @Test
    fun `executor skips actionable intent without delegation target`() {
        val node = ExecutorNode()
        val state =
            BertBotState(
                currentIntent =
                    BertBotIntent(
                        summary = "Routine follow-up",
                        actionable = true,
                        priority = BertBotPriority.ROUTINE,
                    ),
                delegationDecision =
                    BertBotDelegationDecision(
                        attempted = true,
                        reason = "no_sub_agent_match",
                    ),
                pendingTasks = mutableListOf("Routine follow-up"),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals(false, updated.intentResolved)
        assertTrue(updated.executionSummary.contains("Skipped delegation due to missing sub-agent target"))
    }

    @Test
    fun `executor resolves non actionable intent without delegation`() {
        val node = ExecutorNode()
        val state =
            BertBotState(
                currentIntent =
                    BertBotIntent(
                        summary = "No follow-up required",
                        actionable = false,
                        priority = BertBotPriority.NONE,
                    ),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.intentResolved)
        assertTrue(updated.executionSummary.contains("No delegated execution required"))
    }
}
