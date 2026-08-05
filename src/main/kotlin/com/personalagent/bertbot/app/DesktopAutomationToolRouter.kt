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
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger

internal class DesktopAutomationToolRouter(
    private val runtimeConfiguration: DesktopAutomationRuntimeConfiguration,
    private val transport: DesktopAutomationMcpTransport = StdioDesktopAutomationMcpTransport(runtimeConfiguration),
) : ToolRouter {
    override val id: String = "desktop_automation"
    private var discoveredTools: List<DesktopAutomationDiscoveredTool>? = null

    override fun toolDefinitions(): List<JsonObject> {
        if (!runtimeConfiguration.enabled) {
            return emptyList()
        }

        val tools = discoverTools() ?: return emptyList()
        return tools.map { tool ->
            val proxy = JsonObject()
            proxy.addProperty("name", proxyToolName(tool.name))
            proxy.addProperty("description", "Desktop automation proxy for '${tool.name}': ${tool.description}")
            proxy.add("inputSchema", tool.inputSchema)
            proxy
        }
    }

    override fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        if (!runtimeConfiguration.enabled) {
            return true to "Desktop automation is disabled. Set BERTBOT_DESKTOP_AUTOMATION_ENABLED=true to enable it."
        }

        if (toolName.isNullOrBlank()) {
            return null
        }

        val normalized = toolName.removePrefix(runtimeConfiguration.toolNamePrefix)
        if (normalized == toolName) {
            return null
        }

        val tools = discoverTools()
        val toolByProxyName = tools?.associateBy { proxyToolName(it.name) }
        val targetTool = toolByProxyName?.get(toolName)
        if (targetTool == null) {
            return true to "Unknown desktop automation proxy tool: $toolName"
        }

        return transport.callTool(targetTool.name, params)
    }

    private fun discoverTools(): List<DesktopAutomationDiscoveredTool>? {
        discoveredTools?.let { return it }
        val fetched = transport.listTools() ?: return null
        discoveredTools = fetched
        return fetched
    }

    private fun proxyToolName(name: String): String = "${runtimeConfiguration.toolNamePrefix}$name"
}

internal interface DesktopAutomationMcpTransport {
    fun listTools(): List<DesktopAutomationDiscoveredTool>?

    fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String>
}

internal data class DesktopAutomationDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

private class StdioDesktopAutomationMcpTransport(
    private val runtimeConfiguration: DesktopAutomationRuntimeConfiguration,
) : DesktopAutomationMcpTransport {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val logger = Logger.getLogger(StdioDesktopAutomationMcpTransport::class.java.name)

    override fun listTools(): List<DesktopAutomationDiscoveredTool>? {
        return runSession(operationName = "tools/list") {
            initializeSession()
            val response = request(method = "tools/list", id = 2, params = JsonObject())
            val result = response.objectValue("result") ?: return@runSession null
            val tools = result.arrayValue("tools") ?: return@runSession emptyList()
            tools.mapNotNull { element ->
                val tool = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val name = tool.stringValue("name") ?: return@mapNotNull null
                DesktopAutomationDiscoveredTool(
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
        return runSession(operationName = "tools/call:$toolName") {
            initializeSession()
            val params = JsonObject()
            params.addProperty("name", toolName)
            params.add("arguments", arguments)
            val response = request(method = "tools/call", id = 3, params = params)
            val error = response.objectValue("error")
            if (error != null) {
                val message = error.stringValue("message") ?: "unknown desktop automation MCP error"
                return@runSession true to "Desktop automation call failed: $message"
            }

            val result =
                response.objectValue("result")
                    ?: return@runSession true to "Desktop automation call failed: missing result"
            val isError = result.booleanValue("isError") ?: false
            val text = result.textContentOrJson(gson)
            isError to text
        } ?: (true to "Desktop automation MCP process did not return a response.")
    }

    private fun <T> runSession(
        operationName: String,
        block: SessionContext.() -> T?,
    ): T? {
        val command = desktopAutomationCommand(runtimeConfiguration)
        return runCatching {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            configureNodePackageManagerEnvironment(processBuilder, command)
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
        }.onFailure { throwable ->
            logger.log(
                Level.WARNING,
                "Desktop automation MCP $operationName failed (command='${runtimeConfiguration.command}', argsCount=${runtimeConfiguration.args.size}).",
                throwable,
            )
        }.getOrNull()
    }

    private fun configureNodePackageManagerEnvironment(
        processBuilder: ProcessBuilder,
        command: List<String>,
    ) {
        if (command.none { it.equals("npx", ignoreCase = true) }) {
            return
        }

        val cacheDirectory = Paths.get(System.getProperty("java.io.tmpdir"), "bertbot-npm-cache", System.nanoTime().toString())
        runCatching { Files.createDirectories(cacheDirectory) }
        processBuilder.environment()["NPM_CONFIG_CACHE"] = cacheDirectory.toString()
    }

    private fun desktopAutomationCommand(configuration: DesktopAutomationRuntimeConfiguration): List<String> {
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
        private val nonJsonOutputLines = ArrayDeque<String>()

        fun initializeSession() {
            val params = JsonObject()
            params.addProperty("protocolVersion", "2024-11-05")
            params.add("capabilities", JsonObject())
            val clientInfo = JsonObject()
            clientInfo.addProperty("name", "bertbot-desktop-automation-proxy")
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
                            val line = reader.readLine()
                            if (line == null) {
                                error(
                                    "Desktop automation MCP process terminated before responding. recentOutput=${formatRecentOutput()}",
                                )
                            }
                            val json = parseJsonObject(line) ?: continue
                            val id = json.get("id")
                            if (id != null && id.isJsonPrimitive && id.asInt == requestId) {
                                return@submit json
                            }
                        }
                        error("Desktop automation MCP response loop ended unexpectedly.")
                    }.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                error("Timed out waiting for desktop automation MCP response. recentOutput=${formatRecentOutput()}")
            } finally {
                executor.shutdownNow()
            }
        }

        private fun parseJsonObject(line: String): JsonObject? {
            return runCatching { JsonParser.parseString(line) }
                .getOrNull()
                ?.asJsonObjectOrNull()
                ?: run {
                    recordNonJsonOutput(line)
                    null
                }
        }

        private fun recordNonJsonOutput(line: String) {
            if (line.isBlank()) {
                return
            }
            if (nonJsonOutputLines.size >= 6) {
                nonJsonOutputLines.removeFirst()
            }
            nonJsonOutputLines.addLast(line.trim())
        }

        private fun formatRecentOutput(): String {
            if (nonJsonOutputLines.isEmpty()) {
                return "none"
            }
            return nonJsonOutputLines.joinToString(separator = " | ")
        }
    }
}

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
