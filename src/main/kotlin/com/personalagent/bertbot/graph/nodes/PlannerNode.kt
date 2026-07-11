package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

interface IntentionParser {
    fun parse(message: String): BertBotIntent
}

class KeywordIntentionParser(
    nonActionableMessages: Set<String>,
) : IntentionParser {
    private val normalizedNonActionableMessages =
        nonActionableMessages
            .asSequence()
            .map { normalizeMessageForMatching(it) }
            .filter { it.isNotBlank() }
            .toSet()

    override fun parse(message: String): BertBotIntent {
        if (isNonActionableMessage(message)) {
            return BertBotIntent(summary = "No follow-up required")
        }

        val priority =
            if (message.contains("urgent", ignoreCase = true) ||
                message.contains("priority", ignoreCase = true)
            ) {
                BertBotPriority.URGENT
            } else {
                BertBotPriority.ROUTINE
            }

        return BertBotIntent(
            summary = if (priority == BertBotPriority.URGENT) "Urgent follow-up required" else "Routine follow-up",
            actionable = true,
            priority = priority,
        )
    }

    private fun isNonActionableMessage(message: String): Boolean {
        val normalized = normalizeMessageForMatching(message)
        if (normalized.isBlank()) {
            return true
        }

        return normalizedNonActionableMessages.contains(normalized)
    }
}

class PlannerNode(
    private val intentionParser: IntentionParser,
) : BertBotGraphNode {
    constructor() : this(KeywordIntentionParser(DEFAULT_NON_ACTIONABLE_MESSAGES))

    constructor(nonActionableMessages: Set<String>) : this(KeywordIntentionParser(nonActionableMessages))

    override val id: String = NodeIds.PLANNER

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        val intent = intentionParser.parse(state.lastUserMessage)
        state.currentIntent = intent

        if (!intent.actionable) {
            TraceLogger.intentParsed(tracingContext, "priority_signal=none")
            state.executionSummary.add(intent.summary)
            return state
        }

        state.pendingTasks.add(intent.summary)
        TraceLogger.intentParsed(tracingContext, "priority_signal=${intent.priority.name.lowercase()}")
        state.executionSummary.add("Planned follow-up")
        return state
    }

    private companion object {
        val DEFAULT_NON_ACTIONABLE_MESSAGES: Set<String> =
            setOf("hi", "hello", "hey", "thanks", "thank you", "ok", "okay")
    }
}

private fun normalizeMessageForMatching(message: String): String =
    message
        .trim()
        .lowercase()
        .replace(Regex("[!.?]+$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
