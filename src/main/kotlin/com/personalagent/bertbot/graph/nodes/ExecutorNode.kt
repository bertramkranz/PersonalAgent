package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

class ExecutorNode : BertBotGraphNode {
    override val id: String = NodeIds.EXECUTOR

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.delegationPlan.isNotEmpty()) {
            TraceLogger.skillInvoked(tracingContext, "skill=delegated_workflow_runner")
            state.executionSummary.add("Executed delegated workflow")
            TraceLogger.skillCompleted(tracingContext, "skill=delegated_workflow_runner")
        }
        return state
    }
}
