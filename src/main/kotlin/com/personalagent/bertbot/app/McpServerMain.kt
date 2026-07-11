package com.personalagent.bertbot.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.system.exitProcess

private const val MCP_SERVER_NAME = "bertbot"
private const val MCP_SERVER_VERSION = "1.0.0"
private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val ASK_BERTBOT_TOOL_NAME = "ask_bertbot"

private val gson = GsonBuilder().disableHtmlEscaping().create()

fun main() {
    val runtime = BertBotRuntimeFactory.create()
    if (runtime == null) {
        System.err.println("BertBot MCP server could not start because the AI provider API key is missing.")
        exitProcess(1)
    }

    val dispatcher = McpRequestDispatcher { prompt -> runtime.respondTo(prompt, emitFallbackMessage = false) }

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
        if (toolName != ASK_BERTBOT_TOOL_NAME) {
            return errorResponse(requestId, -32601, "Unknown tool: ${toolName ?: "<missing>"}")
        }

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

        val tool = JsonObject()
        tool.addProperty("name", ASK_BERTBOT_TOOL_NAME)
        tool.addProperty("description", "Pass a prompt to BertBot and return the orchestration response.")

        val inputSchema = JsonObject()
        inputSchema.addProperty("type", "object")
        val properties = JsonObject()

        val promptSchema = JsonObject()
        promptSchema.addProperty("type", "string")
        promptSchema.addProperty("description", "Prompt to send to BertBot.")
        properties.add("prompt", promptSchema)

        inputSchema.add("properties", properties)
        val required = JsonArray()
        required.add("prompt")
        inputSchema.add("required", required)
        tool.add("inputSchema", inputSchema)

        tools.add(tool)
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
