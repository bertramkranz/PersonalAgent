package com.personalagent.bertbot.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import kotlin.system.exitProcess

private const val MCP_SERVER_NAME = "bertbot"
private const val MCP_SERVER_VERSION = "1.0.0"
private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val ASK_BERTBOT_TOOL_NAME = "ask_bertbot"
private const val BERTBOT_STATUS_TOOL_NAME = "bertbot_status"

private val gson = GsonBuilder().disableHtmlEscaping().create()

fun main() {
    val runtime = BertBotRuntimeFactory.create()
    if (runtime == null) {
        System.err.println("BertBot MCP server could not start because the AI provider API key is missing.")
        exitProcess(1)
    }

    val dispatcher =
        McpRequestDispatcher(
            respondToPrompt = { prompt -> runtime.respondTo(prompt, emitFallbackMessage = false) },
            statusProvider = {
                """
                Connected to $MCP_SERVER_NAME MCP server.
                Active tool surface: $ASK_BERTBOT_TOOL_NAME, $BERTBOT_STATUS_TOOL_NAME
                Runtime provider: ${runtime.aiRuntimeConfiguration.provider}
                Runtime model: ${runtime.aiRuntimeConfiguration.model}
                Session check timestamp: ${Instant.now()}
                """.trimIndent()
            },
        )

    // Use stderr for startup diagnostics so stdout remains clean JSON-RPC for MCP transport.
    System.err.println(
        """
        BertBot MCP server started.
        Server: $MCP_SERVER_NAME v$MCP_SERVER_VERSION
        Tools: $ASK_BERTBOT_TOOL_NAME, $BERTBOT_STATUS_TOOL_NAME
        Provider: ${runtime.aiRuntimeConfiguration.provider}
        Model: ${runtime.aiRuntimeConfiguration.model}
        """.trimIndent(),
    )

    try {
        runMcpSession(
            readLine = ::readlnOrNull,
            writeLine = ::println,
            dispatcher = dispatcher,
        )
    } catch (e: Exception) {
        System.err.println("BertBot MCP server error: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    } finally {
        runtime.close()
    }
}

internal fun runMcpSession(
    readLine: () -> String?,
    writeLine: (String) -> Unit,
    dispatcher: McpRequestDispatcher,
) {
    while (true) {
        val rawMessage = readLine() ?: break
        val response = dispatcher.handle(rawMessage) ?: continue
        writeLine(response)
    }
}

internal class McpRequestDispatcher(
    private val respondToPrompt: (String) -> String?,
    private val statusProvider: () -> String = {
        "Connected to $MCP_SERVER_NAME MCP server. Active tool surface: $ASK_BERTBOT_TOOL_NAME, $BERTBOT_STATUS_TOOL_NAME"
    },
) {
    fun handle(rawMessage: String): String? {
        val request = parseRequest(rawMessage) ?: return errorResponse(null, -32700, "Invalid JSON")
        val requestId = request.id

        if (requestId == null) {
            return null
        }

        return when (request.method) {
            "initialize" -> successResponse(requestId, buildInitializeResult())
            "initialized" -> null
            "ping" -> successResponse(requestId, JsonObject())
            "tools/list" -> successResponse(requestId, buildToolsListResult())
            "tools/call" -> handleToolCall(requestId, request.params)
            else -> errorResponse(requestId, -32601, "Method not found: ${request.method}")
        }
    }

    private fun handleToolCall(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val toolName = params.stringValue("name") ?: params.stringValue("toolName")
        return when (toolName) {
            ASK_BERTBOT_TOOL_NAME -> handleAskBertBot(requestId, params)
            BERTBOT_STATUS_TOOL_NAME -> toolResultResponse(requestId, false, statusProvider())
            else -> errorResponse(requestId, -32601, "Unknown tool: ${toolName ?: "<missing>"}")
        }
    }

    private fun handleAskBertBot(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val arguments = params.objectValue("arguments") ?: params
        val prompt =
            arguments.stringValue("prompt")
                ?: arguments.stringValue("input")
                ?: arguments.stringValue("text")
                ?: arguments.stringValue("query")

        if (prompt.isNullOrBlank()) {
            return toolResultResponse(requestId, true, "Missing prompt input for $ASK_BERTBOT_TOOL_NAME.")
        }

        return try {
            val response = respondToPrompt(prompt)
            if (response.isNullOrBlank()) {
                toolResultResponse(requestId, true, "BertBot did not return a response.")
            } else {
                toolResultResponse(requestId, false, response)
            }
        } catch (e: Exception) {
            toolResultResponse(requestId, true, "BertBot failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun buildInitializeResult(): JsonObject {
        val result = JsonObject()
        result.addProperty("protocolVersion", MCP_PROTOCOL_VERSION)

        val serverInfo = JsonObject()
        serverInfo.addProperty("name", MCP_SERVER_NAME)
        serverInfo.addProperty("version", MCP_SERVER_VERSION)
        result.add("serverInfo", serverInfo)

        val capabilities = JsonObject()
        val tools = JsonObject()
        tools.addProperty("listChanged", false)
        capabilities.add("tools", tools)
        result.add("capabilities", capabilities)

        return result
    }

    private fun buildToolsListResult(): JsonObject {
        val result = JsonObject()
        val tools = JsonArray()

        val askTool = JsonObject()
        askTool.addProperty("name", ASK_BERTBOT_TOOL_NAME)
        askTool.addProperty("description", "Pass a prompt to BertBot and return the orchestration response.")

        val askInputSchema = JsonObject()
        askInputSchema.addProperty("type", "object")
        val askProperties = JsonObject()

        val promptSchema = JsonObject()
        promptSchema.addProperty("type", "string")
        promptSchema.addProperty("description", "Prompt to send to BertBot.")
        askProperties.add("prompt", promptSchema)

        askInputSchema.add("properties", askProperties)
        val askRequired = JsonArray()
        askRequired.add("prompt")
        askInputSchema.add("required", askRequired)
        askTool.add("inputSchema", askInputSchema)

        val statusTool = JsonObject()
        statusTool.addProperty("name", BERTBOT_STATUS_TOOL_NAME)
        statusTool.addProperty("description", "Return BertBot MCP backend status for this session.")

        val statusInputSchema = JsonObject()
        statusInputSchema.addProperty("type", "object")
        statusInputSchema.add("properties", JsonObject())
        statusInputSchema.add("required", JsonArray())
        statusTool.add("inputSchema", statusInputSchema)

        tools.add(askTool)
        tools.add(statusTool)
        result.add("tools", tools)
        return result
    }

    private fun toolResultResponse(
        requestId: JsonElement,
        isError: Boolean,
        message: String,
    ): String {
        val result = JsonObject()
        val content = JsonArray()
        val textContent = JsonObject()
        textContent.addProperty("type", "text")
        textContent.addProperty("text", message)
        content.add(textContent)
        result.add("content", content)
        result.addProperty("isError", isError)
        return successResponse(requestId, result)
    }

    private fun parseRequest(rawMessage: String): JsonRpcRequest? {
        return try {
            val json = JsonParser.parseString(rawMessage).asJsonObject
            JsonRpcRequest(
                id = json.get("id"),
                method = json.get("method")?.asString,
                params = json.objectValue("params") ?: JsonObject(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun successResponse(
        requestId: JsonElement,
        result: JsonObject,
    ): String {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", requestId)
        response.add("result", result)
        return gson.toJson(response)
    }

    private fun errorResponse(
        requestId: JsonElement?,
        code: Int,
        message: String,
    ): String {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", requestId ?: JsonNull.INSTANCE)

        val error = JsonObject()
        error.addProperty("code", code)
        error.addProperty("message", message)
        response.add("error", error)

        return gson.toJson(response)
    }
}

private data class JsonRpcRequest(
    val id: JsonElement?,
    val method: String?,
    val params: JsonObject,
)

private fun JsonObject.stringValue(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
