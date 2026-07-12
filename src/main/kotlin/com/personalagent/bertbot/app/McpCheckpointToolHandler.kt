package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint

internal class McpCheckpointToolHandler(
    private val listCheckpoints: (scopeKey: String?) -> List<BertBotCheckpoint>,
    private val latestCheckpoint: (scopeKey: String?) -> BertBotCheckpoint?,
    private val checkpointById: (checkpointId: String, scopeKey: String?) -> BertBotCheckpoint?,
    private val rollbackToCheckpoint: (checkpointId: String, scopeKey: String?) -> BertBotState,
    private val rollbackPolicy: CheckpointRollbackPolicyConfiguration = CheckpointRollbackPolicyConfiguration(),
) {
    fun list(params: JsonObject): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val scopeKey = arguments.stringValue("scopeKey")
        val checkpoints = listCheckpoints(scopeKey)
        if (checkpoints.isEmpty()) {
            return false to "No checkpoints found."
        }

        val lines = checkpoints.joinToString(separator = "\n") { it.mcpCheckpointSummary() }
        return false to lines
    }

    fun latest(params: JsonObject): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val scopeKey = arguments.stringValue("scopeKey")
        val checkpoint = latestCheckpoint(scopeKey) ?: return false to "No checkpoints found."
        return false to checkpoint.mcpCheckpointDetails()
    }

    fun get(params: JsonObject): Pair<Boolean, String> {
        val arguments = params.argumentsOrSelf()
        val checkpointId = arguments.stringValue("checkpointId") ?: return true to "Missing required field: checkpointId"
        val scopeKey = arguments.stringValue("scopeKey")
        val checkpoint = checkpointById(checkpointId, scopeKey) ?: return true to "Checkpoint not found."
        return false to checkpoint.mcpCheckpointDetails()
    }

    fun rollback(params: JsonObject): Pair<Boolean, String> {
        val policyError = rollbackPolicyError()
        if (policyError != null) {
            return true to policyError
        }

        val arguments = params.argumentsOrSelf()
        val checkpointId = arguments.stringValue("checkpointId") ?: return true to "Missing required field: checkpointId"
        val confirmed = arguments.booleanValue("confirm") ?: false
        if (rollbackPolicy.requireConfirm && !confirmed) {
            return true to "Rollback requires confirm=true."
        }

        val scopeKey = arguments.stringValue("scopeKey")
        val restored =
            runCatching {
                rollbackToCheckpoint(checkpointId, scopeKey)
            }.getOrElse { error ->
                return true to "Rollback failed: ${error.message ?: "unknown error"}"
            }
        return false to "Rollback completed. lastUserMessage='${restored.lastUserMessage}' intentResolved=${restored.intentResolved}"
    }

    fun rollbackLatest(params: JsonObject): Pair<Boolean, String> {
        val policyError = rollbackPolicyError()
        if (policyError != null) {
            return true to policyError
        }

        val arguments = params.argumentsOrSelf()
        val confirmed = arguments.booleanValue("confirm") ?: false
        if (rollbackPolicy.requireConfirm && !confirmed) {
            return true to "Rollback requires confirm=true."
        }

        val scopeKey = arguments.stringValue("scopeKey")
        val checkpoint = latestCheckpoint(scopeKey) ?: return true to "No checkpoints found."
        val restored =
            runCatching {
                rollbackToCheckpoint(checkpoint.checkpointId, scopeKey)
            }.getOrElse { error ->
                return true to "Rollback failed: ${error.message ?: "unknown error"}"
            }
        return false to
            "Rollback completed to latest checkpoint ${checkpoint.checkpointId}. " +
            "lastUserMessage='${restored.lastUserMessage}' intentResolved=${restored.intentResolved}"
    }

    fun policy(): Pair<Boolean, String> {
        val lines =
            listOf(
                "environment=${rollbackPolicy.environment}",
                "protectedEnvironment=${rollbackPolicy.isProtectedEnvironment}",
                "rollbackEnabled=${rollbackPolicy.rollbackEnabled}",
                "requireConfirm=${rollbackPolicy.requireConfirm}",
                "allowInProtectedEnvironment=${rollbackPolicy.allowInProtectedEnvironment}",
            )
        return false to lines.joinToString(separator = "\n")
    }

    private fun rollbackPolicyError(): String? {
        if (!rollbackPolicy.rollbackEnabled) {
            return "Checkpoint rollback is disabled by configuration."
        }
        if (rollbackPolicy.isProtectedEnvironment && !rollbackPolicy.allowInProtectedEnvironment) {
            return "Checkpoint rollback is blocked in protected environment '${rollbackPolicy.environment}'. " +
                "Set BERTBOT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED=true to override."
        }
        return null
    }
}

private fun BertBotCheckpoint.mcpCheckpointSummary(): String =
    "checkpointId=$checkpointId scopeKey=$scopeKey nodeId=${nodeId ?: "<none>"} createdAtEpochMillis=$createdAtEpochMillis"

private fun BertBotCheckpoint.mcpCheckpointDetails(): String =
    listOf(
        "checkpointId=$checkpointId",
        "scopeKey=$scopeKey",
        "traceId=${traceId ?: "<none>"}",
        "nodeId=${nodeId ?: "<none>"}",
        "createdAtEpochMillis=$createdAtEpochMillis",
        "lastUserMessage=${state.lastUserMessage}",
        "pendingTasks=${state.pendingTasks.size}",
        "executionSummary=${state.executionSummary.size}",
        "intentResolved=${state.intentResolved}",
    ).joinToString(separator = "\n")

private fun JsonObject.booleanValue(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        return null
    }
    return runCatching { element.asBoolean }.getOrNull()
}
