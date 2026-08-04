package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.Incident
import com.personalagent.bertbot.graph.model.IncidentSeverity
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncidentCommanderNodeTest {
    @Test
    fun `incident commander escalates low confidence incidents for approval`() {
        val node = IncidentCommanderNode()
        val state =
            BertBotState(
                activeIncidents =
                    mutableListOf(
                        Incident(
                            incidentId = "i-1",
                            severity = IncidentSeverity.WARNING,
                            category = "low_evidence_confidence",
                            rootCause = "evidence_confidence_below_threshold",
                        ),
                    ),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.requiresUserApproval)
        assertTrue(updated.recoveryStrategies.isNotEmpty())
        assertTrue(updated.incidentLog.isNotEmpty())
    }

    @Test
    fun `incident commander applies fallback on delegation incidents`() {
        val node = IncidentCommanderNode()
        val state =
            BertBotState(
                activeIncidents =
                    mutableListOf(
                        Incident(
                            incidentId = "i-2",
                            severity = IncidentSeverity.WARNING,
                            category = "delegation_unassigned",
                            rootCause = "no_sub_agent_match",
                        ),
                    ),
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertEquals("architect", updated.selectedSubAgent)
        assertTrue(updated.delegationDecision?.attempted == true)
    }
}
