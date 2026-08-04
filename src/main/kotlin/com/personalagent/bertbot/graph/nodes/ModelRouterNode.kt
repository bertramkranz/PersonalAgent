package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.config.ModelSelectionStrategy
import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.ModelRoutingDecision
import com.personalagent.bertbot.graph.model.TokenMetadata
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.ModelCapabilityRegistry
import java.util.Locale

class ModelRouterNode(
    private val registry: ModelCapabilityRegistry = ModelCapabilityRegistry(),
    private val strategy: ModelSelectionStrategy = ModelSelectionStrategy(),
) : BertBotGraphNode {
    override val id: String = NodeIds.MODEL_ROUTER

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        val complexitySignal = complexitySignal(state)
        val selectedModel = if (complexitySignal >= COMPLEXITY_THRESHOLD) strategy.reasoningModel else strategy.primaryModel

        val inputTokens = registry.estimateTokens(state.lastUserMessage) + registry.estimateTokens(state.memorySummary.joinToString("\n"))
        val outputTokens = DEFAULT_OUTPUT_TOKEN_ESTIMATE
        val estimatedCost = registry.estimateCost(selectedModel, inputTokens, outputTokens)

        val decision =
            ModelRoutingDecision(
                selectedModelId = selectedModel,
                reasoning = if (selectedModel == strategy.reasoningModel) "complexity_routed" else "default_low_cost_route",
                fallbackModelIds = listOf(strategy.primaryModel, strategy.reasoningModel).distinct().filterNot { it == selectedModel },
                estimatedCostUsd = estimatedCost,
            )

        state.selectedModel = selectedModel
        state.modelRoutingDecision = decision
        state.estimatedCostForCurrentTurn = estimatedCost
        state.actualCostForCurrentTurn = 0.0
        state.tokenCountingMetadata =
            TokenMetadata(
                inputTokensEstimate = inputTokens,
                outputTokensEstimate = outputTokens,
            )

        TraceLogger.info(
            tracingContext,
            "model_selected",
            "model=$selectedModel estimated_cost_usd=${formatCost(estimatedCost)} complexity=$complexitySignal",
        )

        if (estimatedCost > strategy.costBudgetPerRequestUsd) {
            TraceLogger.warn(
                tracingContext,
                "cost_budget_warning",
                "estimated_cost_usd=${formatCost(estimatedCost)} budget_usd=${formatCost(strategy.costBudgetPerRequestUsd)}",
            )
        }
        return state
    }

    private fun complexitySignal(state: BertBotState): Int {
        var score = 0
        if (state.currentIntent?.priority == BertBotPriority.URGENT) {
            score += 2
        }
        if (state.researchPlan != null) {
            score += 2
        }
        if (state.pendingTasks.size >= 2) {
            score += 1
        }
        if (state.evidenceSources.size >= 3) {
            score += 1
        }
        if (state.lastUserMessage.length >= 180) {
            score += 1
        }
        return score
    }

    private fun formatCost(cost: Double): String = String.format(Locale.ROOT, "%.6f", cost)

    private companion object {
        const val COMPLEXITY_THRESHOLD = 3
        const val DEFAULT_OUTPUT_TOKEN_ESTIMATE = 350
    }
}
