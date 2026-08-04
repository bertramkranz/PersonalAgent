package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.EvidenceSource
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import java.util.Locale

class EvidenceJudgeNode : BertBotGraphNode {
    override val id: String = NodeIds.EVIDENCE_JUDGE

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.pendingTasks.isEmpty()) {
            TraceLogger.info(tracingContext, "evidence_validation_skipped", "reason=no_pending_tasks")
            return state
        }

        val sources = mutableListOf<EvidenceSource>()
        sources += EvidenceSource(sourceType = "user_message", details = state.lastUserMessage.take(180))

        if (state.researchPlan != null) {
            sources += EvidenceSource(sourceType = "research_plan", details = state.researchPlan?.goal.orEmpty())
        }

        if (state.memorySummary.isNotEmpty()) {
            sources += EvidenceSource(sourceType = "memory_summary", details = "entries=${state.memorySummary.size}")
        }

        if (state.profileSummary.isNotEmpty()) {
            sources += EvidenceSource(sourceType = "profile_summary", details = "entries=${state.profileSummary.size}")
        }

        state.evidenceSources = sources
        state.evidenceConfidence = confidenceFor(sources)

        val confidenceText = String.format(Locale.ROOT, "%.2f", state.evidenceConfidence)
        state.executionSummary.add("Validated evidence ($confidenceText confidence)")
        TraceLogger.info(
            tracingContext,
            "evidence_validated",
            "source_count=${sources.size} confidence=$confidenceText",
        )
        return state
    }

    private fun confidenceFor(sources: List<EvidenceSource>): Double {
        return when {
            sources.size >= 4 -> 0.9
            sources.size == 3 -> 0.75
            sources.size == 2 -> 0.6
            else -> 0.45
        }
    }
}
