package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

class PlannerNode : BertBotGraphNode {
    override val id: String = NodeIds.PLANNER

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.lastUserMessage.contains("urgent", ignoreCase = true) ||
            state.lastUserMessage.contains("priority", ignoreCase = true)
        ) {
            state.pendingTasks.add("Urgent follow-up required")
            TraceLogger.intentParsed(tracingContext, "priority_signal=urgent")
        } else {
            state.pendingTasks.add("Routine follow-up")
            TraceLogger.intentParsed(tracingContext, "priority_signal=routine")
        }
        state.executionSummary.add("Planned follow-up")
        return state
    }
}
