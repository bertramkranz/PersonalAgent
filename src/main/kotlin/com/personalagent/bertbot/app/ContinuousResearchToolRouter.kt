package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal const val RESEARCH_LIST_TOOL_NAME = "repo_improvement_recommendations"
internal const val RESEARCH_RUN_NOW_TOOL_NAME = "repo_improvement_run_now"

internal class ContinuousResearchToolRouter(
    private val service: ContinuousImprovementResearchService,
) {
    fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        return when (toolName) {
            RESEARCH_LIST_TOOL_NAME -> handleList(params)
            RESEARCH_RUN_NOW_TOOL_NAME -> handleRunNow(params)
            else -> null
        }
    }

    fun toolDefinitions(): List<JsonObject> =
        listOf(
            buildResearchToolDefinition(
                RESEARCH_LIST_TOOL_NAME,
                "List continuous repository improvement recommendations.",
            ) {
                property("category", "string", "Optional category filter: kotlin, mcp, ai, security, productivity, performance.")
                property("limit", "number", "Maximum number of recommendations to return (default 10, max 100).")
            },
            buildResearchToolDefinition(
                RESEARCH_RUN_NOW_TOOL_NAME,
                "Trigger a manual improvement research cycle immediately.",
            ) {
                property("reason", "string", "Optional reason to include in trace logs.")
            },
        )

    private fun handleList(params: JsonObject): Pair<Boolean, String> {
        val arguments = readObjectValue(params, "arguments") ?: params
        val category = readStringValue(arguments, "category")
        val limit = (readIntValue(arguments, "limit") ?: 10).coerceIn(1, 100)
        val recommendations = service.listRecommendations(limit = limit, category = category)
        if (recommendations.isEmpty()) {
            return false to "No recommendations available for the requested filter."
        }

        val text =
            recommendations.joinToString(separator = "\n\n") { recommendation ->
                buildString {
                    append("key=${recommendation.key}\n")
                    append("title=${recommendation.title}\n")
                    append("category=${recommendation.category} score=${"%.2f".format(recommendation.score)} status=${recommendation.status.name.lowercase()}\n")
                    append("impact=${recommendation.impact} effort=${recommendation.effort} confidence=${recommendation.confidence}\n")
                    append("rationale=${recommendation.rationale}\n")
                    append("evidence=${recommendation.evidence.joinToString(" | ")}\n")
                    append("updatedAt=${recommendation.updatedAt}")
                }
            }
        return false to text
    }

    private fun handleRunNow(params: JsonObject): Pair<Boolean, String> {
        val arguments = readObjectValue(params, "arguments") ?: params
        val reason = readStringValue(arguments, "reason") ?: "manual_trigger"
        val report = service.runNow(reason)
        val summary =
            buildString {
                append("trigger=${report.trigger.name.lowercase()} executed=${report.executed}")
                if (!report.skippedReason.isNullOrBlank()) {
                    append(" skippedReason=${report.skippedReason}")
                }
                append(" recommendationCount=${report.recommendationCount}")
                append(" upsertedCount=${report.upsertedCount}")
                append(" timestamp=${report.timestamp}")
            }
        return false to summary
    }
}

private fun buildResearchToolDefinition(
    name: String,
    description: String,
    schemaBuilder: (ResearchToolSchemaBuilder.() -> Unit)? = null,
): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", name)
    tool.addProperty("description", description)

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")

    val builder = ResearchToolSchemaBuilder()
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

private class ResearchToolSchemaBuilder {
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

private fun readObjectValue(
    source: JsonObject,
    name: String,
): JsonObject? {
    val element = source.get(name) ?: return null
    if (!element.isJsonObject) {
        return null
    }
    return element.asJsonObject
}

private fun readStringValue(
    source: JsonObject,
    name: String,
): String? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asString }.getOrNull()
}

private fun readIntValue(
    source: JsonObject,
    name: String,
): Int? {
    val element: JsonElement = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asInt }.getOrNull()
}
