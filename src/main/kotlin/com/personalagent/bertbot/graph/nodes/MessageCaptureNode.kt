package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext

class MessageCaptureNode : BertBotGraphNode {
    override val id: String = NodeIds.CAPTURE

    override fun execute(
        state: BertBotState,
        tracingContext: TracingContext,
    ): BertBotState {
        if (state.lastUserMessage.isNotBlank()) {
            TraceLogger.intentParsed(tracingContext, "message_length=${state.lastUserMessage.length}")
            state.memorySummary.add("Captured user message metadata: length=${state.lastUserMessage.length}")
            state.executionSummary.add("Captured input")
        }
        return state
    }
}
