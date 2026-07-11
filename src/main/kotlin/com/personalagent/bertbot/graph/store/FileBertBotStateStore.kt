package com.personalagent.bertbot.graph.store

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.model.BertBotDelegationDecision
import com.personalagent.bertbot.graph.model.BertBotIntent
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileBertBotStateStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : BertBotStateStore {
    private val lock = Any()

    override fun load(): BertBotState {
        synchronized(lock) {
            if (!file.exists()) {
                return BertBotState()
            }

            val content = file.readText()
            if (content.isBlank()) {
                return BertBotState()
            }

            return try {
                loadPersistedState(content)
            } catch (_: JsonSyntaxException) {
                preserveUnreadableFile(file, "state")
                BertBotState()
            }
        }
    }

    override fun save(state: BertBotState) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            writeTextAtomically(file, gson.toJson(PersistedBertBotStateSnapshot.fromState(state)))
        }
    }

    private fun loadPersistedState(content: String): BertBotState {
        val snapshot = gson.fromJson(content, PersistedBertBotStateSnapshot::class.java)
        if (snapshot?.schemaVersion == CURRENT_SCHEMA_VERSION) {
            return snapshot.toState()
        }

        val legacySnapshot = gson.fromJson(content, LegacyPersistedBertBotStateSnapshot::class.java)
        if (legacySnapshot?.schemaVersion == LEGACY_SCHEMA_VERSION && legacySnapshot.state != null) {
            return legacySnapshot.state
        }

        return gson.fromJson(content, BertBotState::class.java) ?: BertBotState()
    }
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
