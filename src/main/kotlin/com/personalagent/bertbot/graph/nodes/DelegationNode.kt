package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.app.RoutingHint
import com.personalagent.bertbot.app.RoutingHintRuntimeConfiguration
import com.personalagent.bertbot.graph.model.BertBotDelegationDecision
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

internal class DelegationNode(
    private val registry: SubAgentRegistry = SubAgentRegistry(),
    private val hintConfiguration: RoutingHintRuntimeConfiguration = RoutingHintRuntimeConfiguration(),
    private val hintProvider: ((scopeKey: String, routeKey: String) -> RoutingHint?)? = null,
    private val scopeKeyProvider: ((BertBotState) -> String)? = null,
) : BertBotGraphNode {
    override val id: String = NodeIds.DELEGATION

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        val currentIntent = state.currentIntent
        val shouldDelegate = currentIntent?.actionable ?: state.pendingTasks.isNotEmpty()
        if (shouldDelegate) {
            val taskSummary =
                listOfNotNull(currentIntent?.summary?.takeIf { it.isNotBlank() }, state.lastUserMessage.takeIf { it.isNotBlank() })
                    .joinToString(separator = "; ")
            TraceLogger.skillInvoked(tracingContext, "skill=sub_agent_matcher task_length=${taskSummary.length}")
            val candidates = registry.describeMatchesDetailed(taskSummary)
            val scopeKey = scopeKeyProvider?.invoke(state) ?: "global"
            val match = selectMatchWithHints(candidates, scopeKey, taskSummary, tracingContext)
            if (match != null) {
                val selectedSubAgent = match.id
                TraceLogger.info(
                    tracingContext,
                    "delegation_requested",
                    "from=bertbot to=$selectedSubAgent task_length=${taskSummary.length}",
                )
                state.selectedSubAgent = selectedSubAgent
                state.delegationDecision =
                    BertBotDelegationDecision(
                        attempted = true,
                        selectedSubAgentId = selectedSubAgent,
                        reason = "matched_sub_agent",
                    )
                state.delegationPlan.add("Delegate to ${match.name} (id=${match.id}; ${match.description})")
                state.executionSummary.add("Prepared delegation to ${match.id}")
                TraceLogger.subAgentSelected(tracingContext, "sub_agent=${match.id}")
            } else {
                state.selectedSubAgent = null
                state.delegationDecision =
                    BertBotDelegationDecision(
                        attempted = true,
                        selectedSubAgentId = null,
                        reason = "no_sub_agent_match",
                    )
                state.executionSummary.add("No matching sub-agent found")
                TraceLogger.subAgentSelected(tracingContext, "sub_agent=unassigned")
                TraceLogger.info(
                    tracingContext,
                    "delegation_skipped",
                    "reason=no_sub_agent_match task_length=${taskSummary.length}",
                )
            }
            TraceLogger.skillCompleted(tracingContext, "skill=sub_agent_matcher")
        } else {
            state.delegationDecision = BertBotDelegationDecision(attempted = false, reason = "no_actionable_intent")
        }
        return state
    }

    private fun selectMatchWithHints(
        candidates: List<com.personalagent.bertbot.agents.SubAgentDefinition>,
        scopeKey: String,
        taskSummary: String,
        tracingContext: TracingContext,
    ): com.personalagent.bertbot.agents.SubAgentDefinition? {
        if (candidates.isEmpty()) {
            return null
        }

        val baseline =
            candidates.maxByOrNull { candidate ->
                candidate.skills.count { skill -> taskSummary.lowercase().contains(skill.lowercase()) }
            }

        if (!hintConfiguration.enabled || hintProvider == null) {
            return baseline
        }

        val weighted =
            candidates.map { candidate ->
                val routeKey = "delegation:$scopeKey:${candidate.id}"
                val hint = hintProvider.invoke(scopeKey, routeKey)
                val score = (hint?.score ?: 1.0) * candidate.skills.count { skill -> taskSummary.lowercase().contains(skill.lowercase()) }
                WeightedCandidate(candidate = candidate, score = score, hint = hint)
            }

        val selected = weighted.maxByOrNull { it.score } ?: return baseline
        val hint = selected.hint
        if (hint != null) {
            TraceLogger.info(
                tracingContext,
                "routing_hint_applied",
                "selected=${selected.candidate.id} ${hint.reason} score=${"%.2f".format(selected.score)}",
            )
        }

        return selected.candidate
    }

    private data class WeightedCandidate(
        val candidate: com.personalagent.bertbot.agents.SubAgentDefinition,
        val score: Double,
        val hint: RoutingHint?,
    )
}
