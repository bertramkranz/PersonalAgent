package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val SESSION_HISTORY_LIST_TOOL_NAME = "session_history_list"
internal const val SESSION_HISTORY_CLEAR_TOOL_NAME = "session_history_clear"
internal const val SESSION_HISTORY_SEARCH_TOOL_NAME = "session_history_search"

internal class SessionHistoryToolRouter(
    private val listEntries: (limit: Int, scopeKey: String?) -> List<SessionHistoryEntry>,
    private val searchEntries: (query: String, limit: Int, scopeKey: String?) -> List<SessionHistoryEntry>,
    private val clearEntries: (scopeKey: String?) -> Boolean,
) : ToolRouter {
    override val id: String = "session_history"

    constructor(store: SessionHistoryStore) : this(
        listEntries = { limit, scopeKey ->
            if (scopeKey.isNullOrBlank()) {
                store.list(limit)
            } else {
                store.withScope(scopeKey) { store.list(limit) }
            }
        },
        searchEntries = { query, limit, scopeKey ->
            if (scopeKey.isNullOrBlank()) {
                store.search(query, limit)
            } else {
                store.withScope(scopeKey) { store.search(query, limit) }
            }
        },
        clearEntries = { scopeKey ->
            if (scopeKey.isNullOrBlank()) {
                store.clear()
                true
            } else {
                store.withScope(scopeKey) {
                    store.clear()
                    true
                }
            }
        },
    )

    override fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        val request = ToolInvocationRequestMapper.from(toolName, params)
        val arguments = request.arguments

        return when (request.toolName) {
            SESSION_HISTORY_LIST_TOOL_NAME -> handleList(arguments)
            SESSION_HISTORY_SEARCH_TOOL_NAME -> handleSearch(arguments)
            SESSION_HISTORY_CLEAR_TOOL_NAME -> handleClear(arguments)
            else -> null
        }
    }

    override fun toolDefinitions(): List<JsonObject> =
        listOf(
            buildSessionHistoryToolDefinition(
                name = SESSION_HISTORY_LIST_TOOL_NAME,
                description = "List persisted session history turns for a scope.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("limit", "number", "Maximum number of turns to return (default 50, max 1000).")
            },
            buildSessionHistoryToolDefinition(
                name = SESSION_HISTORY_CLEAR_TOOL_NAME,
                description = "Clear persisted session history turns for a scope.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("confirm", "boolean", "Must be true to execute clear.")
                required("confirm")
            },
            buildSessionHistoryToolDefinition(
                name = SESSION_HISTORY_SEARCH_TOOL_NAME,
                description = "Search persisted session history turns for a scope.",
            ) {
                property("query", "string", "Case-insensitive text query.")
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("limit", "number", "Maximum number of matching turns to return (default 50, max 1000).")
                required("query")
            },
        )

    private fun handleList(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = sessionHistoryStringValue(arguments, "scopeKey")
        val limit = (sessionHistoryIntValue(arguments, "limit") ?: 50).coerceIn(1, 1000)
        val entries = listEntries(limit, scopeKey)
        if (entries.isEmpty()) {
            return false to "No session history entries found."
        }

        val rendered =
            entries.joinToString(separator = "\n") { entry ->
                "${entry.timestamp} ${entry.role.name}: ${entry.text}" +
                    (entry.traceId?.let { trace -> " [traceId=$trace]" } ?: "")
            }
        return false to rendered
    }

    private fun handleSearch(arguments: JsonObject): Pair<Boolean, String> {
        val query = sessionHistoryStringValue(arguments, "query")?.trim().orEmpty()
        if (query.isBlank()) {
            return true to "Missing required field: query"
        }
        val scopeKey = sessionHistoryStringValue(arguments, "scopeKey")
        val limit = (sessionHistoryIntValue(arguments, "limit") ?: 50).coerceIn(1, 1000)
        val entries = searchEntries(query, limit, scopeKey)
        if (entries.isEmpty()) {
            return false to "No session history search matches found."
        }

        val rendered =
            entries.joinToString(separator = "\n") { entry ->
                "${entry.timestamp} ${entry.role.name}: ${entry.text}" +
                    (entry.traceId?.let { trace -> " [traceId=$trace]" } ?: "")
            }
        return false to rendered
    }

    private fun handleClear(arguments: JsonObject): Pair<Boolean, String> {
        val confirm = sessionHistoryBooleanValue(arguments, "confirm") ?: false
        if (!confirm) {
            return true to "Set confirm=true to clear session history."
        }

        val scopeKey = sessionHistoryStringValue(arguments, "scopeKey")
        val cleared = clearEntries(scopeKey)
        return if (cleared) {
            false to "Session history cleared."
        } else {
            true to "Session history clear failed."
        }
    }
}

private fun buildSessionHistoryToolDefinition(
    name: String,
    description: String,
    schemaBuilder: (SessionHistoryToolSchemaBuilder.() -> Unit)? = null,
): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", name)
    tool.addProperty("description", description)

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")

    val builder = SessionHistoryToolSchemaBuilder()
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

private class SessionHistoryToolSchemaBuilder {
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

private fun sessionHistoryStringValue(
    source: JsonObject,
    name: String,
): String? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asString }.getOrNull()
}

private fun sessionHistoryIntValue(
    source: JsonObject,
    name: String,
): Int? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asInt }.getOrNull()
}

private fun sessionHistoryBooleanValue(
    source: JsonObject,
    name: String,
): Boolean? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asBoolean }.getOrNull()
}
