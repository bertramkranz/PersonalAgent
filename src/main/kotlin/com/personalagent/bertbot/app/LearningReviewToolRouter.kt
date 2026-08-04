package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val LEARNING_REVIEW_LIST_TOOL_NAME = "learning_review_list_pending"
internal const val LEARNING_REVIEW_APPROVE_TOOL_NAME = "learning_review_approve"
internal const val LEARNING_REVIEW_REJECT_TOOL_NAME = "learning_review_reject"

internal class LearningReviewToolRouter(
    private val listPending: (limit: Int, scopeKey: String?) -> List<LearningReviewRequest>,
    private val approve: (requestId: String, scopeKey: String?, note: String?) -> LearningReviewDecisionOutcome,
    private val reject: (requestId: String, scopeKey: String?, note: String?) -> LearningReviewDecisionOutcome,
) : ToolRouter {
    override val id: String = "learning_review"

    override fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        val request = ToolInvocationRequestMapper.from(toolName, params)
        val args = request.arguments

        return when (request.toolName) {
            LEARNING_REVIEW_LIST_TOOL_NAME -> handleList(args)
            LEARNING_REVIEW_APPROVE_TOOL_NAME -> handleApprove(args)
            LEARNING_REVIEW_REJECT_TOOL_NAME -> handleReject(args)
            else -> null
        }
    }

    override fun toolDefinitions(): List<JsonObject> =
        listOf(
            buildLearningReviewToolDefinition(
                name = LEARNING_REVIEW_LIST_TOOL_NAME,
                description = "List pending learning-review write actions for a scope.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("limit", "number", "Maximum requests to return (default 50, max 1000).")
            },
            buildLearningReviewToolDefinition(
                name = LEARNING_REVIEW_APPROVE_TOOL_NAME,
                description = "Approve and apply one pending learning-review write action.",
            ) {
                property("requestId", "string", "Pending learning-review request id.")
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("note", "string", "Optional decision note for audit history.")
                required("requestId")
            },
            buildLearningReviewToolDefinition(
                name = LEARNING_REVIEW_REJECT_TOOL_NAME,
                description = "Reject one pending learning-review write action.",
            ) {
                property("requestId", "string", "Pending learning-review request id.")
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("note", "string", "Optional decision note for audit history.")
                required("requestId")
            },
        )

    private fun handleList(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = learningReviewStringValue(arguments, "scopeKey")
        val limit = (learningReviewIntValue(arguments, "limit") ?: 50).coerceIn(1, 1000)
        val entries = listPending(limit, scopeKey)
        if (entries.isEmpty()) {
            return false to "No pending learning-review requests found."
        }

        val rendered =
            entries.joinToString(separator = "\n") { request ->
                "requestId=${request.requestId} type=${request.writeType.name} createdAt=${request.createdAt}" +
                    (request.traceId?.let { trace -> " traceId=$trace" } ?: "") +
                    (request.lastApplyFailedAt?.let { failedAt -> " lastApplyFailedAt=$failedAt" } ?: "") +
                    (request.lastApplyFailureReason?.let { reason -> " lastApplyFailureReason=$reason" } ?: "")
            }
        return false to rendered
    }

    private fun handleApprove(arguments: JsonObject): Pair<Boolean, String> {
        val requestId =
            learningReviewStringValue(arguments, "requestId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: requestId"
        val scopeKey = learningReviewStringValue(arguments, "scopeKey")
        val note = learningReviewStringValue(arguments, "note")
        val outcome = approve(requestId, scopeKey, note)
        val decided =
            outcome.request
                ?: return true to (outcome.message ?: "Learning review request not found.")
        return false to "Approved learning-review request ${decided.requestId}."
    }

    private fun handleReject(arguments: JsonObject): Pair<Boolean, String> {
        val requestId =
            learningReviewStringValue(arguments, "requestId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: requestId"
        val scopeKey = learningReviewStringValue(arguments, "scopeKey")
        val note = learningReviewStringValue(arguments, "note")
        val outcome = reject(requestId, scopeKey, note)
        val decided =
            outcome.request
                ?: return true to (outcome.message ?: "Learning review request not found.")
        return false to "Rejected learning-review request ${decided.requestId}."
    }
}

internal data class LearningReviewDecisionOutcome(
    val request: LearningReviewRequest? = null,
    val message: String? = null,
)

private fun buildLearningReviewToolDefinition(
    name: String,
    description: String,
    schemaBuilder: (LearningReviewToolSchemaBuilder.() -> Unit)? = null,
): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", name)
    tool.addProperty("description", description)

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")

    val builder = LearningReviewToolSchemaBuilder()
    schemaBuilder?.invoke(builder)
    inputSchema.add("properties", builder.properties)
    if (builder.required.isNotEmpty()) {
        val requiredArray = JsonArray()
        builder.required.forEach { propertyName -> requiredArray.add(propertyName) }
        inputSchema.add("required", requiredArray)
    }

    tool.add("inputSchema", inputSchema)
    return tool
}

private class LearningReviewToolSchemaBuilder {
    val properties: JsonObject = JsonObject()
    val required = linkedSetOf<String>()

    fun property(
        name: String,
        type: String,
        description: String,
    ) {
        val property = JsonObject()
        property.addProperty("type", type)
        property.addProperty("description", description)
        properties.add(name, property)
    }

    fun required(vararg names: String) {
        names.forEach { name -> required.add(name) }
    }
}

private fun learningReviewStringValue(
    source: JsonObject,
    name: String,
): String? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asString }.getOrNull()
}

private fun learningReviewIntValue(
    source: JsonObject,
    name: String,
): Int? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asInt }.getOrNull()
}
