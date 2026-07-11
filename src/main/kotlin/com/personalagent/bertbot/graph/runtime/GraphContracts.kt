package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

interface BertBotGraphNode {
    val id: String

    fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState
}

interface BertBotStateStore {
    fun load(): BertBotState

    fun save(state: BertBotState)
}

data class BertBotGraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val condition: (BertBotState) -> Boolean,
)

data class BertBotGraphDefinition(
    val entryNodeId: String,
    val nodes: List<BertBotGraphNode>,
    val edges: List<BertBotGraphEdge>,
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
)

interface StateValidator<T> {
    fun validate(value: T): ValidationResult
}

data class StateHandoffValidator<T>(
    val fromNodeId: String,
    val toNodeId: String,
    val validator: StateValidator<T>,
)

class MaxTurnsExceededException(
    val maxTurns: Int,
    val fallbackMessage: String,
) : RuntimeException("Maximum orchestration turns exceeded: $maxTurns")

class InvalidStateHandoffException(
    fromNodeId: String,
    toNodeId: String,
    errors: List<String>,
) : RuntimeException("Invalid handoff from $fromNodeId to $toNodeId: ${errors.joinToString(separator = "; ")}")
