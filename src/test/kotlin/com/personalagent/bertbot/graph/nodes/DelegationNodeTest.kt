package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.agents.SubAgentDefinition
import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegationNodeTest {
    @Test
    fun `delegation node records explicit selection for actionable intent`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "architect",
                            name = "Architect",
                            description = "Reviews system architecture",
                            skills = setOf("architecture", "review"),
                        ),
                    ),
            )
        val node = DelegationNode(registry)
        val state =
            BertBotState(
                lastUserMessage = "please review architecture",
                currentIntent =
                    BertBotIntent(
                        summary = "Routine follow-up",
                        actionable = true,
                        priority = BertBotPriority.ROUTINE,
                    ),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals("architect", updated.selectedSubAgent)
        assertEquals(true, updated.delegationDecision?.attempted)
        assertEquals("architect", updated.delegationDecision?.selectedSubAgentId)
        assertEquals("matched_sub_agent", updated.delegationDecision?.reason)
        assertTrue(updated.delegationPlan.single().contains("Architect"))
    }

    @Test
    fun `delegation node records no match for actionable intent`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "architect",
                            name = "Architect",
                            description = "Reviews system architecture",
                            skills = setOf("architecture", "review"),
                        ),
                    ),
            )
        val node = DelegationNode(registry)
        val state =
            BertBotState(
                lastUserMessage = "banana shipment details",
                currentIntent =
                    BertBotIntent(
                        summary = "Routine follow-up",
                        actionable = true,
                        priority = BertBotPriority.ROUTINE,
                    ),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals(null, updated.selectedSubAgent)
        assertEquals(true, updated.delegationDecision?.attempted)
        assertEquals(null, updated.delegationDecision?.selectedSubAgentId)
        assertEquals("no_sub_agent_match", updated.delegationDecision?.reason)
        assertTrue(updated.executionSummary.contains("No matching sub-agent found"))
    }
}
