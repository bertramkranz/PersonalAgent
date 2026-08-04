package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.InvestigationPlan
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceJudgeNodeTest {
    @Test
    fun `evidence judge records sources and confidence when pending tasks exist`() {
        val node = EvidenceJudgeNode()
        val state =
            BertBotState(
                lastUserMessage = "analyze this request",
                pendingTasks = mutableListOf("Routine follow-up"),
                researchPlan = InvestigationPlan(goal = "analyze this request"),
                memorySummary = mutableListOf("memory entry"),
                profileSummary = mutableListOf("profile entry"),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals(4, updated.evidenceSources.size)
        assertTrue(updated.evidenceConfidence >= 0.75)
        assertTrue(updated.executionSummary.any { it.contains("Validated evidence", ignoreCase = true) })
    }

    @Test
    fun `evidence judge skips when no pending tasks exist`() {
        val node = EvidenceJudgeNode()
        val state = BertBotState(lastUserMessage = "hello")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.evidenceSources.isEmpty())
        assertEquals(0.0, updated.evidenceConfidence)
    }
}
