package com.personalagent.bertbot.graph.nodes

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode

class MessageCaptureNode : BertBotGraphNode {
    override val id: String = NodeIds.CAPTURE

    override fun execute(state: BertBotState): BertBotState {
        if (state.lastUserMessage.isNotBlank()) {
            state.memorySummary.add("Captured user message: ${state.lastUserMessage}")
            state.executionSummary.add("Captured input")
        }
        return state
    }
}
