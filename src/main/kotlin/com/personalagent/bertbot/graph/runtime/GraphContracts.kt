package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

interface BertBotGraphNode {
    val id: String

    fun execute(state: BertBotState): BertBotState
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
