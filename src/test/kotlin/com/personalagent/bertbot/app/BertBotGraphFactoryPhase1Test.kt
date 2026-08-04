package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BertBotGraphFactoryPhase1Test {
    @Test
    fun `default graph performs research planning evidence validation and safety approval gating`() {
        val graph = BertBotGraphFactory.create(stateStore = InMemoryStateStore(), config = BertBotAgentConfig())

        val state = graph.run(BertBotState(lastUserMessage = "delete this record and research safer alternatives"))

        assertNotNull(state.researchPlan)
        assertTrue(state.evidenceSources.isNotEmpty())
        assertTrue(state.evidenceConfidence > 0.0)
        assertTrue(state.requiresUserApproval)
        assertEquals("requires_user_approval", state.delegationDecision?.reason)
        assertTrue(state.safetyCheckResults.last().requiresApproval)
        assertTrue(state.activeIncidents.isNotEmpty())
        assertTrue(state.recoveryStrategies.isNotEmpty())
        assertTrue(state.executionSummary.any { it.contains("requested user approval", ignoreCase = true) })
    }

    @Test
    fun `default graph routes through delegation when safety checks pass`() {
        val graph = BertBotGraphFactory.create(stateStore = InMemoryStateStore(), config = BertBotAgentConfig())

        val state = graph.run(BertBotState(lastUserMessage = "research and compare runtime architecture options"))

        assertNotNull(state.researchPlan)
        assertTrue(state.evidenceSources.isNotEmpty())
        assertEquals(false, state.requiresUserApproval)
        assertTrue(state.safetyCheckResults.last().passed)
        assertEquals("gpt-4o", state.selectedModel)
        assertTrue(state.estimatedCostForCurrentTurn > 0.0)
        assertTrue(state.delegationDecision?.attempted == true)
    }
}

private class InMemoryStateStore : BertBotStateStore {
    private var state: BertBotState = BertBotState()

    override fun load(): BertBotState = state.copy()

    override fun save(state: BertBotState) {
        this.state = state.copy()
    }
}
