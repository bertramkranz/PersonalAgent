package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class McpToolNames(
    val askBertBot: String,
    val bertBotStatus: String,
    val workspaceListDir: String,
    val workspaceReadFile: String,
    val workspaceSearch: String,
    val polymarketGamma: String,
    val polymarketClob: String,
    val polymarketData: String,
    val ingestionSetApproval: String,
    val ingestionListApprovedSources: String,
    val ingestionIngestManual: String,
    val ingestionChatManual: String,
    val checkpointList: String,
    val checkpointLatest: String,
    val checkpointGet: String,
    val checkpointRollback: String,
    val checkpointRollbackLatest: String,
    val checkpointPolicy: String,
)

internal fun buildInitializeResultPayload(
    protocolVersion: String,
    serverName: String,
    serverVersion: String,
): JsonObject {
    val result = JsonObject()
    result.addProperty("protocolVersion", protocolVersion)

    val serverInfo = JsonObject()
    serverInfo.addProperty("name", serverName)
    serverInfo.addProperty("version", serverVersion)
    result.add("serverInfo", serverInfo)

    val capabilities = JsonObject()
    val tools = JsonObject()
    tools.addProperty("listChanged", false)
    capabilities.add("tools", tools)
    result.add("capabilities", capabilities)

    return result
}

internal fun buildToolsListResultPayload(
    includeIngestionTools: Boolean,
    toolNames: McpToolNames,
    macrofactorToolDefinitions: List<JsonObject> = emptyList(),
    googleWorkspaceToolDefinitions: List<JsonObject> = emptyList(),
    continuousResearchToolDefinitions: List<JsonObject> = emptyList(),
): JsonObject {
    val result = JsonObject()
    val tools = JsonArray()
    baseToolDefinitions(toolNames).forEach { tool -> tools.add(tool) }
    if (includeIngestionTools) {
        ingestionToolDefinitions(toolNames).forEach { tool -> tools.add(tool) }
    }

    macrofactorToolDefinitions.forEach { tool -> tools.add(tool) }
    googleWorkspaceToolDefinitions.forEach { tool -> tools.add(tool) }
    continuousResearchToolDefinitions.forEach { tool -> tools.add(tool) }

    result.add("tools", tools)
    return result
}

@Suppress("LongMethod")
private fun baseToolDefinitions(toolNames: McpToolNames): List<JsonObject> =
    listOf(
        buildToolDefinition(
            toolNames.askBertBot,
            "Pass a prompt to BertBot and return the orchestration response.",
        ) {
            property("prompt", "string", "Prompt to send to BertBot.")
            required("prompt")
        },
        buildToolDefinition(
            toolNames.bertBotStatus,
            "Return BertBot MCP backend status for this session.",
        ),
        buildToolDefinition(
            toolNames.workspaceListDir,
            "List files and directories under a workspace-relative path.",
        ) {
            property("path", "string", "Workspace-relative directory path. Defaults to '.'.")
        },
        buildToolDefinition(
            toolNames.workspaceReadFile,
            "Read a file from the workspace by relative path.",
        ) {
            property("path", "string", "Workspace-relative file path.")
            required("path")
        },
        buildToolDefinition(
            toolNames.workspaceSearch,
            "Search workspace files for a text query.",
        ) {
            property("query", "string", "Case-insensitive text to search for.")
            property("maxResults", "number", "Maximum number of matches to return (default 20).")
            required("query")
        },
        buildToolDefinition(
            toolNames.polymarketGamma,
            "Query Polymarket Gamma API public endpoints (markets, events, search).",
        ) {
            property("operation", "string", "Operation: list_markets, list_events, get_market_by_slug, get_event_by_slug, search, list_markets_keyset, list_events_keyset")
            property("slug", "string", "Required for get_market_by_slug and get_event_by_slug.")
            property("q", "string", "Search query for operation=search.")
            property("limit", "number", "Optional result limit.")
            property("offset", "number", "Optional offset for non-keyset endpoints.")
            property("after_cursor", "string", "Optional cursor for keyset operations.")
            required("operation")
        },
        buildToolDefinition(
            toolNames.polymarketClob,
            "Query Polymarket public CLOB market-data endpoints (book, prices, spreads, history).",
        ) {
            property("operation", "string", "Operation: get_book, get_price, get_midpoint, get_spread, get_last_trade_price, get_prices_history")
            property("token_id", "string", "Token ID used by get_book/get_price/get_midpoint/get_spread/get_last_trade_price.")
            property("side", "string", "BUY or SELL for get_price.")
            property("market", "string", "Market asset ID for get_prices_history.")
            property("startTs", "number", "Optional unix-seconds lower bound for get_prices_history.")
            property("endTs", "number", "Optional unix-seconds upper bound for get_prices_history.")
            property("interval", "string", "Optional interval for get_prices_history: max, all, 1m, 1w, 1d, 6h, 1h.")
            required("operation")
        },
        buildToolDefinition(
            toolNames.polymarketData,
            "Query Polymarket Data API public analytics endpoints (trades, activity, positions, value, holders, OI, leaderboards).",
        ) {
            property("operation", "string", "Operation: get_trades, get_activity, get_positions, get_value, get_holders, get_open_interest, get_trader_leaderboard, get_builder_leaderboard")
            property("user", "string", "Wallet address for user-scoped operations.")
            property("market", "string", "Condition ID filter; comma-separated values supported by upstream API.")
            property("limit", "number", "Optional limit for paginated endpoints.")
            property("offset", "number", "Optional offset for paginated endpoints.")
            required("operation")
        },
        buildToolDefinition(
            toolNames.checkpointList,
            "List checkpoints for a persistence scope.",
        ) {
            property("scopeKey", "string", "Optional persistence scope key. Defaults to global scope.")
        },
        buildToolDefinition(
            toolNames.checkpointLatest,
            "Get the latest checkpoint for a persistence scope.",
        ) {
            property("scopeKey", "string", "Optional persistence scope key. Defaults to global scope.")
        },
        buildToolDefinition(
            toolNames.checkpointGet,
            "Get one checkpoint by id for a persistence scope.",
        ) {
            property("checkpointId", "string", "Checkpoint id to fetch.")
            property("scopeKey", "string", "Optional persistence scope key. Defaults to global scope.")
            required("checkpointId")
        },
        buildToolDefinition(
            toolNames.checkpointRollback,
            "Rollback runtime state to a checkpoint id in a persistence scope.",
        ) {
            property("checkpointId", "string", "Checkpoint id to rollback to.")
            property("scopeKey", "string", "Optional persistence scope key. Defaults to global scope.")
            property("confirm", "boolean", "Must be true to execute rollback.")
            required("checkpointId")
            required("confirm")
        },
        buildToolDefinition(
            toolNames.checkpointRollbackLatest,
            "Rollback runtime state to the latest checkpoint in a persistence scope.",
        ) {
            property("scopeKey", "string", "Optional persistence scope key. Defaults to global scope.")
            property("confirm", "boolean", "Must be true to execute rollback.")
            required("confirm")
        },
        buildToolDefinition(
            toolNames.checkpointPolicy,
            "Show active checkpoint rollback policy and environment guardrails.",
        ),
    )

private fun ingestionToolDefinitions(toolNames: McpToolNames): List<JsonObject> =
    listOf(
        buildToolDefinition(
            toolNames.ingestionSetApproval,
            "Set or revoke approval for a specific external source.",
        ) {
            property("platform", "string", "Source platform (telegram, slack, whatsapp, discord, manual).")
            property("sourceKind", "string", "Source kind (chat, channel, direct_message, business_conversation).")
            property("sourceId", "string", "External source id (chat/channel/conversation).")
            property("workspaceId", "string", "Optional workspace/team/phone-number id.")
            property("scope", "string", "Approval scope (chat, channel, conversation, user).")
            property("approved", "boolean", "True to approve, false to revoke.")
            required("platform")
            required("sourceKind")
            required("sourceId")
            required("approved")
        },
        buildToolDefinition(
            toolNames.ingestionListApprovedSources,
            "List currently approved external ingestion sources.",
        ),
        buildToolDefinition(
            toolNames.ingestionIngestManual,
            "Manually ingest or dry-run one normalized message payload.",
        ) {
            property("platform", "string", "Source platform (telegram, slack, whatsapp, discord, manual).")
            property("sourceKind", "string", "Source kind (chat, channel, direct_message, business_conversation).")
            property("sourceId", "string", "External source id (chat/channel/conversation).")
            property("workspaceId", "string", "Optional workspace/team/phone-number id.")
            property("messageId", "string", "External message id; defaults to generated manual id.")
            property("text", "string", "Message text body.")
            property("senderId", "string", "Optional sender id.")
            property("senderDisplayName", "string", "Optional sender display name.")
            property("threadId", "string", "Optional thread id.")
            property("occurredAt", "string", "Optional ISO timestamp.")
            property("dryRun", "boolean", "Defaults to true.")
            required("platform")
            required("sourceKind")
            required("sourceId")
        },
        buildToolDefinition(
            toolNames.ingestionChatManual,
            "Route one approved external message through BertBot and return the reply payload.",
        ) {
            property("platform", "string", "Source platform (telegram, slack, whatsapp, discord, manual).")
            property("sourceKind", "string", "Source kind (chat, channel, direct_message, business_conversation).")
            property("sourceId", "string", "External source id (chat/channel/conversation).")
            property("workspaceId", "string", "Optional workspace/team/phone-number id.")
            property("messageId", "string", "External message id; defaults to generated manual id.")
            property("text", "string", "Inbound message text.")
            property("senderId", "string", "Optional sender id.")
            property("senderDisplayName", "string", "Optional sender display name.")
            property("threadId", "string", "Optional thread id.")
            property("occurredAt", "string", "Optional ISO timestamp.")
            property("dryRun", "boolean", "Run without persistence writes.")
            required("platform")
            required("sourceKind")
            required("sourceId")
            required("text")
        },
    )

private fun buildToolDefinition(
    name: String,
    description: String,
    configureSchema: ToolSchemaBuilder.() -> Unit = {},
): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", name)
    tool.addProperty("description", description)
    tool.add("inputSchema", ToolSchemaBuilder().apply(configureSchema).build())
    return tool
}

private class ToolSchemaBuilder {
    private val properties = JsonObject()
    private val required = JsonArray()

    fun property(
        name: String,
        type: String,
        description: String,
    ) {
        val schema = JsonObject()
        schema.addProperty("type", type)
        schema.addProperty("description", description)
        properties.add(name, schema)
    }

    fun required(name: String) {
        required.add(name)
    }

    fun build(): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        schema.add("properties", properties)
        schema.add("required", required)
        return schema
    }
}
