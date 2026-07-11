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
        val selectedSubAgent = state.selectedSubAgent
        if (state.delegationPlan.isNotEmpty() && !selectedSubAgent.isNullOrBlank()) {
            TraceLogger.info(
                tracingContext,
                "delegation_started",
                "from=bertbot to=$selectedSubAgent",
            )
            TraceLogger.skillInvoked(tracingContext, "skill=delegated_workflow_runner")
            state.executionSummary.add("Executed delegated workflow")
            TraceLogger.skillCompleted(tracingContext, "skill=delegated_workflow_runner")
            TraceLogger.info(
                tracingContext,
                "delegation_completed",
                "from=$selectedSubAgent to=bertbot",
            )
        } else if (state.pendingTasks.isNotEmpty()) {
            TraceLogger.info(
                tracingContext,
                "delegation_skipped",
                "reason=missing_delegation_target pending_tasks=${state.pendingTasks.size}",
            )
            state.executionSummary.add("Skipped delegation due to missing sub-agent target")
        }
        return state
    }
}
