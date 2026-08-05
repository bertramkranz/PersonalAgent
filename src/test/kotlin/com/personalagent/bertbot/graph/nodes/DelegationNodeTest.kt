package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.agents.SubAgentDefinition
import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `delegation node selects polymarket analyst for market analytics intent`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "polymarket_analyst",
                            name = "Polymarket Analyst",
                            description = "Analyzes Polymarket prices and liquidity",
                            skills = setOf("polymarket", "odds", "open interest", "order book"),
                        ),
                    ),
            )
        val node = DelegationNode(registry)
        val state =
            BertBotState(
                lastUserMessage = "check polymarket open interest and order book depth",
                currentIntent =
                    BertBotIntent(
                        summary = "Polymarket probability analysis",
                        actionable = true,
                        priority = BertBotPriority.ROUTINE,
                    ),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-poly"))

        assertEquals("polymarket_analyst", updated.selectedSubAgent)
        assertEquals("matched_sub_agent", updated.delegationDecision?.reason)
    }

    @Test
    fun `delegation node applies profile model override when preferredModelId is set`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "coder",
                            name = "Coder",
                            description = "Implements code",
                            skills = setOf("coding", "implementation"),
                        ),
                    ),
            )
        val node =
            DelegationNode(
                registry = registry,
                profileModelLookup = { subAgentId, _ -> if (subAgentId == "coder") "gpt-5.4-mini" else null },
            )
        val state =
            BertBotState(
                lastUserMessage = "please write a coding implementation",
                currentIntent = BertBotIntent(summary = "coding task", actionable = true, priority = BertBotPriority.ROUTINE),
                selectedModel = "gpt-5.6-luna",
            )

        val updated = node.execute(state, TracingContext(traceId = "test-override"))

        assertEquals("coder", updated.selectedSubAgent)
        assertEquals("gpt-5.4-mini", updated.selectedModel)
        assertEquals("sub_agent_profile_override", updated.modelRoutingDecision?.reasoning)
    }

    @Test
    fun `delegation node leaves selected model unchanged when no profile model is configured`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "planner",
                            name = "Planner",
                            description = "Plans tasks",
                            skills = setOf("planning", "scheduling"),
                        ),
                    ),
            )
        val node = DelegationNode(registry = registry, profileModelLookup = { _, _ -> null })
        val state =
            BertBotState(
                lastUserMessage = "create a planning schedule",
                currentIntent = BertBotIntent(summary = "planning task", actionable = true, priority = BertBotPriority.ROUTINE),
                selectedModel = "gpt-5.6-luna",
            )

        val updated = node.execute(state, TracingContext(traceId = "test-no-override"))

        assertEquals("planner", updated.selectedSubAgent)
        assertEquals("gpt-5.6-luna", updated.selectedModel)
        assertNull(updated.modelRoutingDecision)
    }

    @Test
    fun `delegation node sets sub_agent_profile_override reasoning on modelRoutingDecision`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "architect",
                            name = "Architect",
                            description = "Designs systems",
                            skills = setOf("architecture", "design"),
                        ),
                    ),
            )
        val node =
            DelegationNode(
                registry = registry,
                profileModelLookup = { subAgentId, _ -> if (subAgentId == "architect") "gpt-5.6-terra" else null },
            )
        val state =
            BertBotState(
                lastUserMessage = "review the architecture and design",
                currentIntent = BertBotIntent(summary = "architecture review", actionable = true, priority = BertBotPriority.ROUTINE),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-architect"))

        assertEquals("gpt-5.6-terra", updated.selectedModel)
        assertEquals("sub_agent_profile_override", updated.modelRoutingDecision?.reasoning)
        assertEquals("gpt-5.6-terra", updated.modelRoutingDecision?.selectedModelId)
    }

    @Test
    fun `delegation node uses highComplexityModelId when complexity_routed signal is set`() {
        val registry =
            SubAgentRegistry(
                definitions =
                    listOf(
                        SubAgentDefinition(
                            id = "architect",
                            name = "Architect",
                            description = "Designs systems",
                            skills = setOf("architecture", "design"),
                        ),
                    ),
            )
        val node =
            DelegationNode(
                registry = registry,
                profileModelLookup = { subAgentId, isComplexTask ->
                    if (subAgentId == "architect") {
                        if (isComplexTask) "gpt-5.6-sol" else "gpt-5.6-terra"
                    } else {
                        null
                    }
                },
            )
        val complexState =
            BertBotState(
                lastUserMessage = "deeply analyze the architecture and design tradeoffs",
                currentIntent =
                    BertBotIntent(summary = "complex architecture review", actionable = true, priority = BertBotPriority.URGENT),
                modelRoutingDecision =
                    com.personalagent.bertbot.graph.model.ModelRoutingDecision(
                        selectedModelId = "gpt-5.6-sol",
                        reasoning = "complexity_routed",
                    ),
            )

        val updated = node.execute(complexState, TracingContext(traceId = "test-complex-architect"))

        assertEquals("architect", updated.selectedSubAgent)
        assertEquals("gpt-5.6-sol", updated.selectedModel)
        assertEquals("sub_agent_profile_override", updated.modelRoutingDecision?.reasoning)
    }
}
