package com.personalagent.bertbot.graph.store

import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.model.BertBotDelegationDecision
import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.model.EvidenceSource
import com.personalagent.bertbot.graph.model.Incident
import com.personalagent.bertbot.graph.model.IncidentLogEntry
import com.personalagent.bertbot.graph.model.InvestigationPlan
import com.personalagent.bertbot.graph.model.ModelRoutingDecision
import com.personalagent.bertbot.graph.model.RecoveryStrategy
import com.personalagent.bertbot.graph.model.SafetyCheckResult
import com.personalagent.bertbot.graph.model.TokenMetadata
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.memory.PersistenceScopeKey
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileBertBotStateStore(
    private val file: File,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) : BertBotStateStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun load(): BertBotState {
        synchronized(lock) {
            val scopedFile = scopedFile()
            val legacyFile = legacyScopedFile()
            val existingFile = if (scopedFile.exists()) scopedFile else legacyFile
            if (!existingFile.exists()) {
                return BertBotState()
            }

            if (existingFile == legacyFile && legacyFile != scopedFile) {
                println("Warning: state store loaded legacy scoped file '${legacyFile.path}' because normalized scoped file '${scopedFile.path}' was not found.")
            }

            val content = existingFile.readText()
            if (content.isBlank()) {
                return BertBotState()
            }

            return try {
                loadPersistedState(content)
            } catch (_: JsonSyntaxException) {
                preserveUnreadableFile(existingFile, "state")
                BertBotState()
            }
        }
    }

    override fun save(state: BertBotState) {
        synchronized(lock) {
            val scopedFile = scopedFile()
            scopedFile.parentFile?.mkdirs()
            writeTextAtomically(scopedFile, codec.encode(PersistedBertBotStateSnapshot.fromState(state)))
        }
    }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        val previousLegacyAlias = legacyScopeAlias.get()
        currentScope.set(PersistenceScopeKey.normalizeForFile(scopeKey))
        legacyScopeAlias.set(PersistenceScopeKey.legacyFileAlias(scopeKey))
        return try {
            action()
        } finally {
            currentScope.set(previous)
            legacyScopeAlias.set(previousLegacyAlias)
        }
    }

    private fun scopedFile(): File {
        val scope = currentScope.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return file
        }
        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }

    private fun legacyScopedFile(): File {
        val scope = legacyScopeAlias.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return file
        }
        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }

    private fun loadPersistedState(content: String): BertBotState {
        val snapshot = codec.decode(content, PersistedBertBotStateSnapshot::class.java)
        if (snapshot?.schemaVersion == CURRENT_SCHEMA_VERSION) {
            return snapshot.toState()
        }

        val legacySnapshot = codec.decode(content, LegacyPersistedBertBotStateSnapshot::class.java)
        if (legacySnapshot?.schemaVersion == LEGACY_SCHEMA_VERSION && legacySnapshot.state != null) {
            return legacySnapshot.state
        }

        return codec.decode(content, BertBotState::class.java) ?: BertBotState()
    }

    private companion object
}

internal data class PersistedBertBotStateSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val traceId: String? = null,
    val lastUserMessage: String = "",
    val pendingTasks: List<String> = emptyList(),
    val delegationPlan: List<String> = emptyList(),
    val memorySummary: List<String> = emptyList(),
    val profileSummary: List<String> = emptyList(),
    val executionSummary: List<String> = emptyList(),
    val currentIntent: BertBotIntent? = null,
    val delegationDecision: BertBotDelegationDecision? = null,
    val researchPlan: InvestigationPlan? = null,
    val evidenceSources: List<EvidenceSource> = emptyList(),
    val evidenceConfidence: Double = 0.0,
    val safetyCheckResults: List<SafetyCheckResult> = emptyList(),
    val requiresUserApproval: Boolean = false,
    val approvalReason: String = "",
    val selectedModel: String? = null,
    val modelRoutingDecision: ModelRoutingDecision? = null,
    val estimatedCostForCurrentTurn: Double = 0.0,
    val actualCostForCurrentTurn: Double = 0.0,
    val tokenCountingMetadata: TokenMetadata? = null,
    val activeIncidents: List<Incident> = emptyList(),
    val recoveryStrategies: List<RecoveryStrategy> = emptyList(),
    val incidentLog: List<IncidentLogEntry> = emptyList(),
    val selectedSubAgent: String? = null,
    val intentResolved: Boolean = false,
) {
    fun toState(): BertBotState =
        BertBotState(
            traceId = traceId,
            lastUserMessage = lastUserMessage,
            pendingTasks = pendingTasks.toMutableList(),
            delegationPlan = delegationPlan.toMutableList(),
            memorySummary = memorySummary.toMutableList(),
            profileSummary = profileSummary.toMutableList(),
            executionSummary = executionSummary.toMutableList(),
            currentIntent = currentIntent,
            delegationDecision = delegationDecision,
            researchPlan = researchPlan,
            evidenceSources = evidenceSources.toMutableList(),
            evidenceConfidence = evidenceConfidence,
            safetyCheckResults = safetyCheckResults.toMutableList(),
            requiresUserApproval = requiresUserApproval,
            approvalReason = approvalReason,
            selectedModel = selectedModel,
            modelRoutingDecision = modelRoutingDecision,
            estimatedCostForCurrentTurn = estimatedCostForCurrentTurn,
            actualCostForCurrentTurn = actualCostForCurrentTurn,
            tokenCountingMetadata = tokenCountingMetadata,
            activeIncidents = activeIncidents.toMutableList(),
            recoveryStrategies = recoveryStrategies.toMutableList(),
            incidentLog = incidentLog.toMutableList(),
            selectedSubAgent = selectedSubAgent,
            intentResolved = intentResolved,
        )

    companion object {
        fun fromState(state: BertBotState): PersistedBertBotStateSnapshot =
            PersistedBertBotStateSnapshot(
                traceId = state.traceId,
                lastUserMessage = state.lastUserMessage,
                pendingTasks = state.pendingTasks.toList(),
                delegationPlan = state.delegationPlan.toList(),
                memorySummary = state.memorySummary.toList(),
                profileSummary = state.profileSummary.toList(),
                executionSummary = state.executionSummary.toList(),
                currentIntent = state.currentIntent,
                delegationDecision = state.delegationDecision,
                researchPlan = state.researchPlan,
                evidenceSources = state.evidenceSources.toList(),
                evidenceConfidence = state.evidenceConfidence,
                safetyCheckResults = state.safetyCheckResults.toList(),
                requiresUserApproval = state.requiresUserApproval,
                approvalReason = state.approvalReason,
                selectedModel = state.selectedModel,
                modelRoutingDecision = state.modelRoutingDecision,
                estimatedCostForCurrentTurn = state.estimatedCostForCurrentTurn,
                actualCostForCurrentTurn = state.actualCostForCurrentTurn,
                tokenCountingMetadata = state.tokenCountingMetadata,
                activeIncidents = state.activeIncidents.toList(),
                recoveryStrategies = state.recoveryStrategies.toList(),
                incidentLog = state.incidentLog.toList(),
                selectedSubAgent = state.selectedSubAgent,
                intentResolved = state.intentResolved,
            )
    }
}

internal data class LegacyPersistedBertBotStateSnapshot(
    val schemaVersion: Int = LEGACY_SCHEMA_VERSION,
    val state: BertBotState? = null,
)

private const val CURRENT_SCHEMA_VERSION = 2
private const val LEGACY_SCHEMA_VERSION = 1

private fun writeTextAtomically(
    target: File,
    content: String,
) {
    val parentDir = target.parentFile ?: File(".")
    parentDir.mkdirs()
    val tempPath = Files.createTempFile(parentDir.toPath(), "${target.nameWithoutExtension}-", ".tmp")
    val tempFile = tempPath.toFile()
    try {
        tempFile.writeText(content)
        try {
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            println("Warning: atomic move unsupported for '${target.path}'. Falling back to non-atomic replace.")
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        runCatching { tempFile.delete() }
        throw e
    }
}

private fun preserveUnreadableFile(
    file: File,
    kind: String,
) {
    val extension = file.extension.takeIf { it.isNotBlank() } ?: "txt"
    val backupFile = File(file.parentFile ?: File("."), "${file.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.$extension")
    runCatching {
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    println("Warning: failed to parse persisted $kind file '${file.path}'. A backup was preserved at '${backupFile.path}'.")
}
