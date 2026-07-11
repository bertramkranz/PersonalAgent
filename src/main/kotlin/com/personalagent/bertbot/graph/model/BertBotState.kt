package com.personalagent.bertbot.graph.model

enum class BertBotPriority {
    NONE,
    ROUTINE,
    URGENT,
}

data class BertBotIntent(
    val summary: String = "",
    val actionable: Boolean = false,
    val priority: BertBotPriority = BertBotPriority.NONE,
)

data class BertBotDelegationDecision(
    val attempted: Boolean = false,
    val selectedSubAgentId: String? = null,
    val reason: String = "",
)

data class BertBotState(
    var traceId: String? = null,
    var lastUserMessage: String = "",
    var pendingTasks: MutableList<String> = mutableListOf(),
    var delegationPlan: MutableList<String> = mutableListOf(),
    var memorySummary: MutableList<String> = mutableListOf(),
    var profileSummary: MutableList<String> = mutableListOf(),
    var executionSummary: MutableList<String> = mutableListOf(),
    var currentIntent: BertBotIntent? = null,
    var delegationDecision: BertBotDelegationDecision? = null,
    var selectedSubAgent: String? = null,
    var intentResolved: Boolean = false,
)
