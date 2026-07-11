package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

class DelegationNode(
    private val registry: SubAgentRegistry = SubAgentRegistry(),
) : BertBotGraphNode {
    override val id: String = NodeIds.DELEGATION

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.pendingTasks.isNotEmpty()) {
            val taskSummary = state.pendingTasks.joinToString(separator = "; ")
            TraceLogger.skillInvoked(tracingContext, "skill=sub_agent_matcher task_length=${taskSummary.length}")
            val match = registry.findBestMatch(taskSummary)
            if (match != null) {
                state.selectedSubAgent = match.name
                state.delegationPlan.add("Delegate to ${match.name} (${match.description})")
                state.executionSummary.add("Prepared delegation to ${match.name}")
                TraceLogger.subAgentSelected(tracingContext, "sub_agent=${match.name}")
            } else {
                state.delegationPlan.add("Delegate to a specialized sub-agent based on task fit")
                state.executionSummary.add("Prepared delegation with no exact match")
                TraceLogger.subAgentSelected(tracingContext, "sub_agent=none")
            }
            TraceLogger.skillCompleted(tracingContext, "skill=sub_agent_matcher")
        }
        return state
    }
}
