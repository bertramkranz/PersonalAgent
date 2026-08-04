package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.InvestigationPlan
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

class ResearchPlannerNode : BertBotGraphNode {
    override val id: String = NodeIds.RESEARCH_PLANNER

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        val message = state.lastUserMessage
        if (!shouldCreatePlan(message, state.currentIntent?.priority)) {
            state.researchPlan = null
            TraceLogger.info(tracingContext, "investigation_plan_skipped", "reason=no_research_signal")
            return state
        }

        val plan =
            InvestigationPlan(
                goal = inferGoal(message),
                steps =
                    listOf(
                        "Collect workspace and runtime evidence.",
                        "Identify candidate changes and risks.",
                        "Propose a constrained implementation path.",
                    ),
                parallelizable = true,
            )

        state.researchPlan = plan
        state.executionSummary.add("Prepared an investigation plan")
        TraceLogger.info(
            tracingContext,
            "investigation_plan_created",
            "goal=${plan.goal} steps=${plan.steps.size} parallelizable=${plan.parallelizable}",
        )
        return state
    }

    private fun shouldCreatePlan(
        message: String,
        priority: BertBotPriority?,
    ): Boolean {
        if (priority == BertBotPriority.URGENT) {
            return true
        }

        val normalized = message.lowercase()
        return RESEARCH_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    private fun inferGoal(message: String): String {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            return "Investigate user request"
        }
        return trimmed.take(200)
    }

    private companion object {
        val RESEARCH_KEYWORDS =
            listOf(
                "research",
                "analyze",
                "investigate",
                "compare",
                "evaluate",
                "optimize",
            )
    }
}
