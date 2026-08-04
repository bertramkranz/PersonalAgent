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

data class InvestigationPlan(
    val goal: String = "",
    val steps: List<String> = emptyList(),
    val parallelizable: Boolean = false,
)

data class EvidenceSource(
    val sourceType: String = "",
    val details: String = "",
)

data class SafetyCheckResult(
    val passed: Boolean = true,
    val reason: String = "",
    val requiresApproval: Boolean = false,
)

data class ModelRoutingDecision(
    val selectedModelId: String = "",
    val reasoning: String = "",
    val fallbackModelIds: List<String> = emptyList(),
    val estimatedCostUsd: Double = 0.0,
)

data class TokenMetadata(
    val inputTokensEstimate: Int = 0,
    val outputTokensEstimate: Int = 0,
    val inputTokensActual: Int? = null,
    val outputTokensActual: Int? = null,
)

enum class IncidentSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}

data class Incident(
    val incidentId: String = "",
    val severity: IncidentSeverity = IncidentSeverity.INFO,
    val category: String = "",
    val rootCause: String = "",
    val retryCount: Int = 0,
)

enum class RecoveryAction {
    RETRY,
    FALLBACK,
    ESCALATE,
    ABORT,
}

data class RecoveryStrategy(
    val strategyId: String = "",
    val incidentId: String = "",
    val action: RecoveryAction = RecoveryAction.RETRY,
    val reasoning: String = "",
    val userApprovalRequired: Boolean = false,
)

data class IncidentLogEntry(
    val timestampEpochMillis: Long = 0,
    val incidentId: String = "",
    val actionTaken: String = "",
    val outcome: String = "",
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
    var researchPlan: InvestigationPlan? = null,
    var evidenceSources: MutableList<EvidenceSource> = mutableListOf(),
    var evidenceConfidence: Double = 0.0,
    var safetyCheckResults: MutableList<SafetyCheckResult> = mutableListOf(),
    var requiresUserApproval: Boolean = false,
    var approvalReason: String = "",
    var selectedModel: String? = null,
    var modelRoutingDecision: ModelRoutingDecision? = null,
    var estimatedCostForCurrentTurn: Double = 0.0,
    var actualCostForCurrentTurn: Double = 0.0,
    var tokenCountingMetadata: TokenMetadata? = null,
    var activeIncidents: MutableList<Incident> = mutableListOf(),
    var recoveryStrategies: MutableList<RecoveryStrategy> = mutableListOf(),
    var incidentLog: MutableList<IncidentLogEntry> = mutableListOf(),
)
