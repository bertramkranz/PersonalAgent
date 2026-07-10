package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode

class ExecutorNode : BertBotGraphNode {
    override val id: String = NodeIds.EXECUTOR

    override fun execute(state: BertBotState): BertBotState {
        if (state.delegationPlan.isNotEmpty()) {
            state.executionSummary.add("Executed delegated workflow")
        }
        return state
    }
}
