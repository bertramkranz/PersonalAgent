package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

class BertBotGraphRunner(
    private val definition: BertBotGraphDefinition,
    private val stateStore: BertBotStateStore,
) {
    fun run(initialState: BertBotState): BertBotState {
        var state =
            stateStore.load().apply {
                lastUserMessage = initialState.lastUserMessage.ifBlank { lastUserMessage }
                pendingTasks = (pendingTasks + initialState.pendingTasks).toMutableList()
                delegationPlan = (delegationPlan + initialState.delegationPlan).toMutableList()
                memorySummary = (memorySummary + initialState.memorySummary).toMutableList()
                executionSummary = (executionSummary + initialState.executionSummary).toMutableList()
            }

        var currentNodeId = definition.entryNodeId
        while (currentNodeId.isNotBlank()) {
            val node = definition.nodes.firstOrNull { it.id == currentNodeId } ?: break
            state = node.execute(state)

            currentNodeId = definition.edges
                .firstOrNull { it.fromNodeId == currentNodeId && it.condition(state) }
                ?.toNodeId
                ?: ""
        }

        stateStore.save(state)
        return state
    }
}
