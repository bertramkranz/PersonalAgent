package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode

class PlannerNode : BertBotGraphNode {
    override val id: String = NodeIds.PLANNER

    override fun execute(state: BertBotState): BertBotState {
        if (state.lastUserMessage.contains("urgent", ignoreCase = true) ||
            state.lastUserMessage.contains("priority", ignoreCase = true)
        ) {
            state.pendingTasks.add("Urgent follow-up required")
        } else {
            state.pendingTasks.add("Routine follow-up")
        }
        state.executionSummary.add("Planned follow-up")
        return state
    }
}
