package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode

class DelegationNode(
    private val registry: SubAgentRegistry = SubAgentRegistry(),
) : BertBotGraphNode {
    override val id: String = NodeIds.DELEGATION

    override fun execute(state: BertBotState): BertBotState {
        if (state.pendingTasks.isNotEmpty()) {
            val taskSummary = state.pendingTasks.joinToString(separator = "; ")
            val match = registry.findBestMatch(taskSummary)
            if (match != null) {
                state.selectedSubAgent = match.name
                state.delegationPlan.add("Delegate to ${match.name} (${match.description})")
                state.executionSummary.add("Prepared delegation to ${match.name}")
            } else {
                state.delegationPlan.add("Delegate to a specialized sub-agent based on task fit")
                state.executionSummary.add("Prepared delegation with no exact match")
            }
        }
        return state
    }
}
