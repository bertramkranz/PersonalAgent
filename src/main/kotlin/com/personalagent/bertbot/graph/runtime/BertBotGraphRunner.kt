package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

class BertBotGraphRunner(
    private val definition: BertBotGraphDefinition,
    private val stateStore: BertBotStateStore,
    private val maxTurns: Int = 5,
    private val handoffValidators: List<StateHandoffValidator<BertBotState>> = emptyList(),
) {
    fun run(initialState: BertBotState): BertBotState {
        val tracingContext = TracingContext(initialState.traceId ?: "")
        val effectiveTracingContext =
            if (tracingContext.traceId.isBlank()) {
                TracingContext()
            } else {
                tracingContext
            }

        var state =
            BertBotState(
                traceId = effectiveTracingContext.traceId,
                lastUserMessage = initialState.lastUserMessage,
                pendingTasks = initialState.pendingTasks.toMutableList(),
                delegationPlan = initialState.delegationPlan.toMutableList(),
                memorySummary = initialState.memorySummary.toMutableList(),
                profileSummary = initialState.profileSummary.toMutableList(),
                executionSummary = initialState.executionSummary.toMutableList(),
                selectedSubAgent = initialState.selectedSubAgent,
                intentResolved = initialState.intentResolved,
            )

        TraceLogger.intentParsed(effectiveTracingContext, "initial_message_length=${state.lastUserMessage.length}")

        var currentNodeId = definition.entryNodeId
        var unresolvedTurns = 0
        val nodeVisitCounts = linkedMapOf<String, Int>()

        while (currentNodeId.isNotBlank()) {
            if (unresolvedTurns >= maxTurns) {
                val fallbackMessage =
                    "I could not complete your request in time, so I stopped to avoid an infinite loop. Please try rephrasing your request."
                TraceLogger.warn(
                    effectiveTracingContext,
                    "max_turns_exceeded",
                    "max_turns=$maxTurns unresolved_turns=$unresolvedTurns",
                )
                throw MaxTurnsExceededException(maxTurns = maxTurns, fallbackMessage = fallbackMessage)
            }

            val node = definition.nodes.firstOrNull { it.id == currentNodeId } ?: break
            nodeVisitCounts[node.id] = (nodeVisitCounts[node.id] ?: 0) + 1
            TraceLogger.info(effectiveTracingContext, "node_start", "node_id=${node.id}")
            state = node.execute(state, effectiveTracingContext)
            TraceLogger.info(effectiveTracingContext, "node_complete", "node_id=${node.id}")

            if (isIntentResolved(state)) {
                unresolvedTurns = 0
            } else {
                unresolvedTurns += 1
            }

            val nextEdge =
                definition.edges
                    .firstOrNull { it.fromNodeId == currentNodeId && it.condition(state) }

            if (nextEdge != null) {
                TraceLogger.transition(
                    effectiveTracingContext,
                    fromNodeId = currentNodeId,
                    toNodeId = nextEdge.toNodeId,
                    details = "state_pending_tasks=${state.pendingTasks.size}",
                )
                validateHandoff(currentNodeId, nextEdge.toNodeId, state, effectiveTracingContext)
            }

            currentNodeId = nextEdge?.toNodeId ?: ""
        }

        stateStore.save(state)
        val nodeVisitSummary = nodeVisitCounts.entries.joinToString(separator = ",") { (node, count) -> "$node:$count" }
        TraceLogger.info(effectiveTracingContext, "graph_node_visits", "counts=$nodeVisitSummary")
        TraceLogger.info(effectiveTracingContext, "graph_completed", "execution_summary_size=${state.executionSummary.size}")
        return state
    }

    private fun isIntentResolved(state: BertBotState): Boolean =
        state.intentResolved

    private fun validateHandoff(
        fromNodeId: String,
        toNodeId: String,
        state: BertBotState,
        context: TracingContext,
    ) {
        handoffValidators
            .filter { validator -> validator.fromNodeId == fromNodeId && validator.toNodeId == toNodeId }
            .forEach { handoff ->
                val result = handoff.validator.validate(state)
                if (!result.isValid) {
                    TraceLogger.warn(
                        context,
                        "handoff_validation_failed",
                        "from=$fromNodeId to=$toNodeId errors=${result.errors.joinToString(separator = "|")}",
                    )
                    throw InvalidStateHandoffException(fromNodeId, toNodeId, result.errors)
                }
            }
    }
}
