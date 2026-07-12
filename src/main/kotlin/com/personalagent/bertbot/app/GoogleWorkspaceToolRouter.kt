package com.personalagent.bertbot.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class GoogleWorkspaceToolRouter(
    private val runtimeConfiguration: GoogleWorkspaceRuntimeConfiguration,
    private val transport: GoogleWorkspaceMcpTransport = StdioGoogleWorkspaceMcpTransport(runtimeConfiguration),
) {
    private var discoveredTools: List<GoogleWorkspaceDiscoveredTool>? = null

    fun toolDefinitions(): List<JsonObject> {
        val tools = discoverTools() ?: return emptyList()
        return tools.map { tool ->
            val proxy = JsonObject()
            proxy.addProperty("name", proxyToolName(tool.name))
            val summary = if (tool.description.isBlank()) "Google Workspace MCP tool proxy." else tool.description
            proxy.addProperty("description", "Google Workspace MCP proxy for '${tool.name}': $summary")
            proxy.add("inputSchema", tool.inputSchema)
            proxy
        }
    }

    fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        val request = ToolInvocationRequestMapper.from(toolName, params)
        val requestedToolName = request.toolName
        if (requestedToolName.isNullOrBlank() || !requestedToolName.startsWith(runtimeConfiguration.toolNamePrefix)) {
            return null
        }

        val tools = discoverTools() ?: return true to "Google Workspace tool discovery failed. Check runtime configuration and process logs."
        val toolByProxyName = tools.associateBy { proxyToolName(it.name) }
        val targetTool = toolByProxyName[requestedToolName]
        if (targetTool == null) {
            return true to "Unknown Google Workspace proxy tool: $requestedToolName"
        }

        return transport.callTool(targetTool.name, request.arguments)
    }

    private fun discoverTools(): List<GoogleWorkspaceDiscoveredTool>? {
        discoveredTools?.let { return it }
        val fetched = transport.listTools() ?: return null
        discoveredTools = fetched
        return fetched
    }

    private fun proxyToolName(name: String): String = "${runtimeConfiguration.toolNamePrefix}$name"
}

internal interface GoogleWorkspaceMcpTransport {
    fun listTools(): List<GoogleWorkspaceDiscoveredTool>?

    fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String>
}

internal data class GoogleWorkspaceDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

private class StdioGoogleWorkspaceMcpTransport(
    private val runtimeConfiguration: GoogleWorkspaceRuntimeConfiguration,
) : GoogleWorkspaceMcpTransport {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun listTools(): List<GoogleWorkspaceDiscoveredTool>? {
        return runSession {
            initializeSession()
            val response = request(method = "tools/list", id = 2, params = JsonObject())
            val result = response.googleWorkspaceObjectValue("result") ?: return@runSession null
            val tools = result.googleWorkspaceArrayValue("tools") ?: return@runSession emptyList()
            tools.mapNotNull { element ->
                val tool = element.googleWorkspaceAsJsonObjectOrNull() ?: return@mapNotNull null
                val name = tool.stringValue("name") ?: return@mapNotNull null
                GoogleWorkspaceDiscoveredTool(
                    name = name,
                    description = tool.stringValue("description") ?: "",
                    inputSchema = tool.objectValue("inputSchema") ?: defaultInputSchema(),
                )
            }
        }
    }

    override fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String> {
        return runSession {
            initializeSession()
            val params = JsonObject()
            params.addProperty("name", toolName)
            params.add("arguments", arguments)
            val response = request(method = "tools/call", id = 3, params = params)
            val error = response.googleWorkspaceObjectValue("error")
            if (error != null) {
                val message = error.stringValue("message") ?: "unknown Google Workspace MCP error"
                return@runSession true to "Google Workspace call failed: $message"
            }

            val result =
                response.googleWorkspaceObjectValue("result")
                    ?: return@runSession true to "Google Workspace call failed: missing result"
            val isError = result.googleWorkspaceBooleanValue("isError") ?: false
            val text = result.googleWorkspaceTextContentOrJson(gson)
            isError to text
        } ?: (true to "Google Workspace MCP process did not return a response.")
    }

    private fun <T> runSession(block: SessionContext.() -> T?): T? {
        return runCatching {
            val processBuilder = ProcessBuilder(googleWorkspaceCommand(runtimeConfiguration)).redirectErrorStream(true)
            val process = processBuilder.start()
            try {
                val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                SessionContext(reader, writer, process, gson, runtimeConfiguration.timeoutSeconds).block()
            } finally {
                process.destroy()
                process.waitFor(250, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }.getOrNull()
    }

    private fun googleWorkspaceCommand(configuration: GoogleWorkspaceRuntimeConfiguration): List<String> {
        val commandParts = listOf(configuration.command) + configuration.args
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        return if (osName.contains("windows")) {
            listOf("cmd.exe", "/c") + commandParts
        } else {
            commandParts
        }
    }

    private fun defaultInputSchema(): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        schema.add("properties", JsonObject())
        schema.add("required", JsonArray())
        return schema
    }

    private data class SessionContext(
        val reader: BufferedReader,
        val writer: BufferedWriter,
        val process: Process,
        val gson: com.google.gson.Gson,
        val timeoutSeconds: Long,
    ) {
        fun initializeSession() {
            val params = JsonObject()
            params.addProperty("protocolVersion", "2024-11-05")
            params.add("capabilities", JsonObject())
            val clientInfo = JsonObject()
            clientInfo.addProperty("name", "bertbot-google-workspace-proxy")
            clientInfo.addProperty("version", "1.0.0")
            params.add("clientInfo", clientInfo)
            request(method = "initialize", id = 1, params = params)
            request(method = "initialized", id = 100, params = JsonObject(), expectResponse = false)
        }

        fun request(
            method: String,
            id: Int,
            params: JsonObject,
            expectResponse: Boolean = true,
        ): JsonObject {
            val request = JsonObject()
            request.addProperty("jsonrpc", "2.0")
            request.addProperty("id", id)
            request.addProperty("method", method)
            request.add("params", params)

            writer.write(gson.toJson(request))
            writer.newLine()
            writer.flush()

            if (!expectResponse) {
                return JsonObject()
            }

            return readResponse(id)
        }

        private fun readResponse(requestId: Int): JsonObject {
            val executor = Executors.newSingleThreadExecutor()
            return try {
                executor
                    .submit<JsonObject> {
                        while (true) {
                            val line = reader.readLine() ?: error("Google Workspace MCP process terminated before responding.")
                            val json = parseJsonObject(line) ?: continue
                            val id = json.get("id")
                            if (id != null && id.isJsonPrimitive && id.asInt == requestId) {
                                return@submit json
                            }
                        }
                        error("Google Workspace MCP response loop ended unexpectedly.")
                    }.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                error("Timed out waiting for Google Workspace MCP response.")
            } finally {
                executor.shutdownNow()
            }
        }

        private fun parseJsonObject(line: String): JsonObject? {
            return runCatching { JsonParser.parseString(line) }
                .getOrNull()
                ?.googleWorkspaceAsJsonObjectOrNull()
        }
    }
}

private fun JsonObject.googleWorkspaceArrayValue(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.googleWorkspaceObjectValue(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.googleWorkspaceBooleanValue(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        return null
    }
    return runCatching { element.asBoolean }.getOrNull()
}

private fun JsonElement.googleWorkspaceAsJsonObjectOrNull(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.googleWorkspaceTextContentOrJson(gson: com.google.gson.Gson): String {
    val content = googleWorkspaceArrayValue("content") ?: return gson.toJson(this)
    val textChunks =
        content
            .mapNotNull { it.googleWorkspaceAsJsonObjectOrNull() }
            .mapNotNull { element -> element.stringValue("text") }

    return if (textChunks.isEmpty()) gson.toJson(this) else textChunks.joinToString(separator = "\n")
}
