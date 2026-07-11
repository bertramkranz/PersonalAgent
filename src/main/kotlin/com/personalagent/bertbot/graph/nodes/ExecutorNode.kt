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
        val delegationDecision = state.delegationDecision
        val selectedSubAgent = delegationDecision?.selectedSubAgentId ?: state.selectedSubAgent
        val actionableIntent = state.currentIntent?.actionable ?: state.pendingTasks.isNotEmpty()
        if (delegationDecision?.attempted == true && !selectedSubAgent.isNullOrBlank()) {
            TraceLogger.info(
                tracingContext,
                "delegation_started",
                "from=bertbot to=$selectedSubAgent",
            )
            TraceLogger.skillInvoked(tracingContext, "skill=delegated_workflow_runner")
            state.executionSummary.add("Executed delegated workflow")
            state.intentResolved = true
            TraceLogger.skillCompleted(tracingContext, "skill=delegated_workflow_runner")
            TraceLogger.info(
                tracingContext,
                "delegation_completed",
                "from=$selectedSubAgent to=bertbot",
            )
        } else if (actionableIntent) {
            val reason = delegationDecision?.reason ?: "missing_delegation_target"
            TraceLogger.info(
                tracingContext,
                "delegation_skipped",
                "reason=$reason pending_tasks=${state.pendingTasks.size}",
            )
            state.executionSummary.add("Skipped delegation due to missing sub-agent target")
        } else {
            state.executionSummary.add("No delegated execution required")
            state.intentResolved = true
        }
        return state
    }
}
