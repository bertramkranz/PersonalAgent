package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.config.ModelSelectionStrategy
import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.EvidenceSource
import com.personalagent.bertbot.graph.model.InvestigationPlan
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelRouterNodeTest {
    @Test
    fun `model router selects reasoning model for complex requests`() {
        val node = ModelRouterNode(strategy = ModelSelectionStrategy(primaryModel = "gpt-5.6-luna", reasoningModel = "gpt-5.6-sol"))
        val state =
            BertBotState(
                lastUserMessage = "research and deeply analyze this architecture decision",
                currentIntent = BertBotIntent(actionable = true, priority = BertBotPriority.URGENT),
                pendingTasks = mutableListOf("Urgent follow-up"),
                researchPlan = InvestigationPlan(goal = "analyze"),
                evidenceSources = mutableListOf(EvidenceSource("user", "request"), EvidenceSource("memory", "summary"), EvidenceSource("profile", "summary")),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals("gpt-5.6-sol", updated.selectedModel)
        assertNotNull(updated.modelRoutingDecision)
        assertTrue(updated.estimatedCostForCurrentTurn > 0.0)
    }

    @Test
    fun `model router selects primary model for routine requests`() {
        val node = ModelRouterNode(strategy = ModelSelectionStrategy(primaryModel = "gpt-5.6-luna", reasoningModel = "gpt-5.6-sol"))
        val state = BertBotState(lastUserMessage = "hello")

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals("gpt-5.6-luna", updated.selectedModel)
        assertNotNull(updated.tokenCountingMetadata)
    }
}
