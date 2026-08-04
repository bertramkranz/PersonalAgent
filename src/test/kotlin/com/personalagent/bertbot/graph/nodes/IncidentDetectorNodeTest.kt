package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TracingContext
import kotlin.test.Test
import kotlin.test.assertTrue

class IncidentDetectorNodeTest {
    @Test
    fun `incident detector reports low evidence and approval incidents`() {
        val node = IncidentDetectorNode()
        val state =
            BertBotState(
                lastUserMessage = "review this",
                pendingTasks = mutableListOf("Routine follow-up"),
                evidenceConfidence = 0.2,
                requiresUserApproval = true,
                approvalReason = "approval_gate",
            )

        val updated = node.execute(state, TracingContext(traceId = "test-trace"))

        assertTrue(updated.activeIncidents.any { it.category == "low_evidence_confidence" })
        assertTrue(updated.activeIncidents.any { it.category == "approval_required" })
    }
}
