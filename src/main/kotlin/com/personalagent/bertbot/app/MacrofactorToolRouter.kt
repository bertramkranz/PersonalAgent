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

internal class MacrofactorToolRouter(
    private val runtimeConfiguration: MacrofactorRuntimeConfiguration,
    private val transport: MacrofactorMcpTransport = StdioMacrofactorMcpTransport(runtimeConfiguration),
) {
    private var discoveredTools: List<MacrofactorDiscoveredTool>? = null

    fun toolDefinitions(): List<JsonObject> {
        val tools = discoverTools() ?: return emptyList()
        return tools.map { tool ->
            val proxy = JsonObject()
            proxy.addProperty("name", proxyToolName(tool.name))
            val summary = if (tool.description.isBlank()) "MacroFactor tool proxy." else tool.description
            proxy.addProperty("description", "MacroFactor proxy for '${tool.name}': $summary")
            proxy.add("inputSchema", tool.inputSchema)
            proxy
        }
    }

    fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        if (toolName.isNullOrBlank() || !toolName.startsWith(runtimeConfiguration.toolNamePrefix)) {
            return null
        }

        if (!runtimeConfiguration.isConfigured) {
            return true to "MacroFactor tools are enabled but missing credentials. Set BERTBOT_MACROFACTOR_USERNAME and BERTBOT_MACROFACTOR_PASSWORD."
        }

        val tools = discoverTools() ?: return true to "MacroFactor tool discovery failed. Check runtime configuration and process logs."
        val toolByProxyName = tools.associateBy { proxyToolName(it.name) }
        val targetTool = toolByProxyName[toolName]
        if (targetTool == null) {
            return true to "Unknown MacroFactor proxy tool: $toolName"
        }

        val arguments = params.objectValue("arguments") ?: JsonObject()
        return transport.callTool(targetTool.name, arguments)
    }

    private fun discoverTools(): List<MacrofactorDiscoveredTool>? {
        discoveredTools?.let { return it }
        val fetched = transport.listTools() ?: return null
        discoveredTools = fetched
        return fetched
    }

    private fun proxyToolName(name: String): String = "${runtimeConfiguration.toolNamePrefix}$name"
}

internal interface MacrofactorMcpTransport {
    fun listTools(): List<MacrofactorDiscoveredTool>?

    fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String>
}

internal data class MacrofactorDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

private class StdioMacrofactorMcpTransport(
    private val runtimeConfiguration: MacrofactorRuntimeConfiguration,
) : MacrofactorMcpTransport {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun listTools(): List<MacrofactorDiscoveredTool>? {
        return runSession {
            initializeSession()
            val response = request(method = "tools/list", id = 2, params = JsonObject())
            val result = response.objectValue("result") ?: return@runSession null
            val tools = result.arrayValue("tools") ?: return@runSession emptyList()
            tools.mapNotNull { element ->
                val tool = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val name = tool.stringValue("name") ?: return@mapNotNull null
                MacrofactorDiscoveredTool(
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
            val error = response.objectValue("error")
            if (error != null) {
                val message = error.stringValue("message") ?: "unknown MacroFactor MCP error"
                return@runSession true to "MacroFactor call failed: $message"
            }

            val result = response.objectValue("result") ?: return@runSession true to "MacroFactor call failed: missing result"
            val isError = result.booleanValue("isError") ?: false
            val text = result.textContentOrJson(gson)
            isError to text
        } ?: (true to "MacroFactor process did not return a response.")
    }

    private fun <T> runSession(block: SessionContext.() -> T?): T? {
        return runCatching {
            val processBuilder =
                ProcessBuilder(listOf(runtimeConfiguration.command) + runtimeConfiguration.args)
                    .redirectErrorStream(true)
            processBuilder.environment().putAll(buildCredentialEnvironment())
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

    private fun defaultInputSchema(): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        schema.add("properties", JsonObject())
        schema.add("required", JsonArray())
        return schema
    }

    private fun buildCredentialEnvironment(): Map<String, String> {
        val username = runtimeConfiguration.username.orEmpty()
        val password = runtimeConfiguration.password.orEmpty()
        if (username.isBlank() || password.isBlank()) {
            return emptyMap()
        }

        return mapOf(
            "MACROFACTOR_USERNAME" to username,
            "MACROFACTOR_PASSWORD" to password,
            "MF_USERNAME" to username,
            "MF_PASSWORD" to password,
        )
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
            clientInfo.addProperty("name", "bertbot-macrofactor-proxy")
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
                            val line = reader.readLine() ?: error("MacroFactor MCP process terminated before responding.")
                            val json = parseJsonObject(line) ?: continue
                            val id = json.get("id")
                            if (id != null && id.isJsonPrimitive && id.asInt == requestId) {
                                return@submit json
                            }
                        }
                        error("MacroFactor MCP response loop ended unexpectedly.")
                    }.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                error("Timed out waiting for MacroFactor MCP response.")
            } finally {
                executor.shutdownNow()
            }
        }

        private fun parseJsonObject(line: String): JsonObject? {
            return runCatching { JsonParser.parseString(line) }
                .getOrNull()
                ?.asJsonObjectOrNull()
        }
    }
}

private fun JsonObject.stringValue(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name)?.asJsonObjectOrNull()

private fun JsonObject.arrayValue(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.booleanValue(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        return null
    }
    return runCatching { element.asBoolean }.getOrNull()
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.textContentOrJson(gson: com.google.gson.Gson): String {
    val content = arrayValue("content") ?: return gson.toJson(this)
    val textChunks =
        content
            .mapNotNull { it.asJsonObjectOrNull() }
            .mapNotNull { element -> element.stringValue("text") }

    return if (textChunks.isEmpty()) gson.toJson(this) else textChunks.joinToString(separator = "\n")
}
