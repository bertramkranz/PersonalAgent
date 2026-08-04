package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotDelegationDecision
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.SafetyCheckResult
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

class SafetyGuardianNode : BertBotGraphNode {
    override val id: String = NodeIds.SAFETY_GUARDIAN

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.pendingTasks.isEmpty()) {
            return state
        }

        val requiresApproval = requiresApproval(state.lastUserMessage)
        val result =
            if (requiresApproval) {
                SafetyCheckResult(
                    passed = false,
                    reason = "high_risk_operation_detected",
                    requiresApproval = true,
                )
            } else {
                SafetyCheckResult(
                    passed = true,
                    reason = "no_high_risk_signal",
                    requiresApproval = false,
                )
            }

        state.safetyCheckResults.add(result)
        state.requiresUserApproval = requiresApproval
        state.approvalReason = if (requiresApproval) "User approval required before delegated execution." else ""

        if (requiresApproval) {
            state.executionSummary.add("Safety Guardian requested user approval")
            state.delegationDecision =
                state.delegationDecision ?: BertBotDelegationDecision(attempted = true, reason = "requires_user_approval")
            TraceLogger.warn(
                tracingContext,
                "safety_check_failed",
                "reason=${result.reason} requires_approval=true",
            )
        } else {
            state.executionSummary.add("Safety Guardian approved execution")
            TraceLogger.info(
                tracingContext,
                "safety_check_passed",
                "reason=${result.reason}",
            )
        }

        return state
    }

    private fun requiresApproval(message: String): Boolean {
        val normalized = message.lowercase()
        return HIGH_RISK_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    private companion object {
        val HIGH_RISK_KEYWORDS =
            listOf(
                "checkout",
                "purchase",
                "buy now",
                "transfer money",
                "delete",
                "drop table",
                "reset",
            )
    }
}
