package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotDelegationDecision
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.Incident
import com.personalagent.bertbot.graph.model.IncidentLogEntry
import com.personalagent.bertbot.graph.model.RecoveryAction
import com.personalagent.bertbot.graph.model.RecoveryStrategy
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import java.util.UUID

class IncidentCommanderNode : BertBotGraphNode {
    override val id: String = NodeIds.INCIDENT_COMMANDER

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.activeIncidents.isEmpty()) {
            return state
        }

        val strategies = state.activeIncidents.map { incident -> selectStrategy(incident) }
        state.recoveryStrategies = strategies.toMutableList()

        strategies.forEach { strategy ->
            applyStrategy(strategy, state)
            TraceLogger.info(
                tracingContext,
                "recovery_strategy_selected",
                "strategy_id=${strategy.strategyId} incident_id=${strategy.incidentId} action=${strategy.action}",
            )
            state.incidentLog.add(
                IncidentLogEntry(
                    timestampEpochMillis = System.currentTimeMillis(),
                    incidentId = strategy.incidentId,
                    actionTaken = strategy.action.name,
                    outcome = if (strategy.userApprovalRequired) "pending_user_approval" else "auto_applied",
                ),
            )
        }

        if (strategies.any { it.userApprovalRequired }) {
            state.requiresUserApproval = true
            val approvalCategories = state.activeIncidents.filter { incident -> requiresApprovalCategory(incident.category) }.joinToString(",") { it.category }
            state.approvalReason = "Incident recovery approval required: $approvalCategories"
            state.executionSummary.add("Incident Commander escalated recovery for approval")
        } else {
            state.executionSummary.add("Incident Commander applied automatic recovery")
        }
        return state
    }

    private fun selectStrategy(incident: Incident): RecoveryStrategy {
        val action =
            when (incident.category) {
                "model_routing_missing" -> RecoveryAction.RETRY
                "delegation_unassigned" -> RecoveryAction.FALLBACK
                "low_evidence_confidence" -> RecoveryAction.ESCALATE
                "approval_required" -> RecoveryAction.ABORT
                else -> RecoveryAction.RETRY
            }

        return RecoveryStrategy(
            strategyId = UUID.randomUUID().toString(),
            incidentId = incident.incidentId,
            action = action,
            reasoning = "policy_${incident.category}",
            userApprovalRequired = requiresApprovalCategory(incident.category),
        )
    }

    private fun requiresApprovalCategory(category: String): Boolean =
        category == "low_evidence_confidence" || category == "approval_required"

    private fun applyStrategy(
        strategy: RecoveryStrategy,
        state: BertBotState,
    ) {
        when (strategy.action) {
            RecoveryAction.RETRY -> {
                if (state.selectedModel.isNullOrBlank()) {
                    state.selectedModel = "gpt-4o-mini"
                }
            }

            RecoveryAction.FALLBACK -> {
                if (!state.requiresUserApproval) {
                    state.delegationDecision =
                        BertBotDelegationDecision(
                            attempted = true,
                            selectedSubAgentId = "architect",
                            reason = "incident_fallback",
                        )
                    state.selectedSubAgent = "architect"
                }
            }

            RecoveryAction.ESCALATE -> {
                state.requiresUserApproval = true
            }

            RecoveryAction.ABORT -> {
                state.intentResolved = true
            }
        }
    }
}
