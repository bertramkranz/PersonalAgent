package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.Incident
import com.personalagent.bertbot.graph.model.IncidentSeverity
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import java.util.UUID

class IncidentDetectorNode : BertBotGraphNode {
    override val id: String = NodeIds.INCIDENT_DETECTOR

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        val incidents = mutableListOf<Incident>()

        if (state.pendingTasks.isNotEmpty() && state.evidenceConfidence in 0.0..0.49) {
            incidents +=
                Incident(
                    incidentId = UUID.randomUUID().toString(),
                    severity = IncidentSeverity.WARNING,
                    category = "low_evidence_confidence",
                    rootCause = "evidence_confidence_below_threshold",
                )
        }

        if (state.pendingTasks.isNotEmpty() && !state.requiresUserApproval && state.selectedModel.isNullOrBlank()) {
            incidents +=
                Incident(
                    incidentId = UUID.randomUUID().toString(),
                    severity = IncidentSeverity.ERROR,
                    category = "model_routing_missing",
                    rootCause = "no_selected_model_for_actionable_request",
                )
        }

        if (!state.requiresUserApproval && state.executionSummary.any { it.contains("Skipped delegation", ignoreCase = true) }) {
            incidents +=
                Incident(
                    incidentId = UUID.randomUUID().toString(),
                    severity = IncidentSeverity.WARNING,
                    category = "delegation_unassigned",
                    rootCause = "no_sub_agent_match",
                )
        }

        if (state.requiresUserApproval) {
            incidents +=
                Incident(
                    incidentId = UUID.randomUUID().toString(),
                    severity = IncidentSeverity.INFO,
                    category = "approval_required",
                    rootCause = state.approvalReason.ifBlank { "approval_gate" },
                )
        }

        state.activeIncidents = incidents
        if (incidents.isNotEmpty()) {
            TraceLogger.warn(
                tracingContext,
                "incidents_detected",
                "count=${incidents.size} categories=${incidents.joinToString(",") { it.category }}",
            )
        }
        return state
    }
}
