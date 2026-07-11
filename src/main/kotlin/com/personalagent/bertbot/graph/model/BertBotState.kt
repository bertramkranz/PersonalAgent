package com.personalagent.bertbot.graph.model

data class BertBotState(
    var traceId: String? = null,
    var lastUserMessage: String = "",
    var pendingTasks: MutableList<String> = mutableListOf(),
    var delegationPlan: MutableList<String> = mutableListOf(),
    var memorySummary: MutableList<String> = mutableListOf(),
    var executionSummary: MutableList<String> = mutableListOf(),
    var selectedSubAgent: String? = null,
)
