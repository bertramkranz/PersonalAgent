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
            val taskSummary = (state.pendingTasks + state.lastUserMessage).joinToString(separator = "; ")
            TraceLogger.skillInvoked(tracingContext, "skill=sub_agent_matcher task_length=${taskSummary.length}")
            val match = registry.findBestMatch(taskSummary)
            if (match != null) {
                val selectedSubAgent = match.id
                TraceLogger.info(
                    tracingContext,
                    "delegation_requested",
                    "from=bertbot to=$selectedSubAgent task_length=${taskSummary.length}",
                )
                state.selectedSubAgent = selectedSubAgent
                state.delegationPlan.add("Delegate to ${match.name} (id=${match.id}; ${match.description})")
                state.executionSummary.add("Prepared delegation to ${match.id}")
                TraceLogger.subAgentSelected(tracingContext, "sub_agent=${match.id}")
            } else {
                state.selectedSubAgent = null
                state.executionSummary.add("No matching sub-agent found")
                TraceLogger.subAgentSelected(tracingContext, "sub_agent=unassigned")
                TraceLogger.info(
                    tracingContext,
                    "delegation_skipped",
                    "reason=no_sub_agent_match task_length=${taskSummary.length}",
                )
            }
            TraceLogger.skillCompleted(tracingContext, "skill=sub_agent_matcher")
        }
        return state
    }
}
