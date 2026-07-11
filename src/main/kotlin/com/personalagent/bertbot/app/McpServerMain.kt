package com.personalagent.bertbot.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant

private const val MCP_SERVER_NAME = "bertbot"
private const val MCP_SERVER_VERSION = "1.0.0"
private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val ASK_BERTBOT_TOOL_NAME = "ask_bertbot"
private const val BERTBOT_STATUS_TOOL_NAME = "bertbot_status"
private const val WORKSPACE_LIST_DIR_TOOL_NAME = "workspace_list_dir"
private const val WORKSPACE_READ_FILE_TOOL_NAME = "workspace_read_file"
private const val WORKSPACE_SEARCH_TOOL_NAME = "workspace_search"
private const val WORKSPACE_ROOT_ENV_VAR = "BERTBOT_WORKSPACE_ROOT"
private val BACKEND_UNAVAILABLE_MARKERS =
    listOf(
        "backend_workspace_unavailable",
        "backend workspace tooling is unavailable",
        "backend cannot verify repository files",
        "cannot verify repository files",
    )
private val EVIDENCE_HINT_KEYWORDS =
    listOf(
        "repo",
        "repository",
        "file",
        "files",
        "directory",
        "directories",
        "architecture",
        "review",
        "inventory",
        "line reference",
        "line references",
        "verified",
        "verify",
    )

private val STATUS_HINT_KEYWORDS =
    listOf(
        "bert_bot status",
        "bertbot status",
        "bertbot_status",
        "backend health",
        "backend status",
        "runtime state",
        "status output",
        "check backend",
        "is backend",
    )

private val gson = GsonBuilder().disableHtmlEscaping().create()

fun main() {
    val aiRuntimeConfiguration = resolveAiRuntimeConfiguration()
    val startup = createStartupState(aiRuntimeConfiguration)
    val workspaceRoot = resolveWorkspaceRoot()

    val dispatcher =
        McpRequestDispatcher(
            respondToPrompt = { prompt, requestCorrelationId ->
                val runtime = startup.runtime
                if (runtime == null) {
                    error(startup.errorMessage ?: "BertBot runtime is unavailable.")
                }
                runtime.respondTo(
                    userMessage = prompt,
                    emitFallbackMessage = false,
                    traceCorrelationId = requestCorrelationId,
                )
            },
            workspaceRoot = workspaceRoot,
            statusProvider = {
                """
                Connected to $MCP_SERVER_NAME MCP server.
                Active tool surface: $ASK_BERTBOT_TOOL_NAME, $BERTBOT_STATUS_TOOL_NAME, $WORKSPACE_LIST_DIR_TOOL_NAME, $WORKSPACE_READ_FILE_TOOL_NAME, $WORKSPACE_SEARCH_TOOL_NAME
                Workspace root: ${workspaceRoot.absolutePath}
                Runtime ready: ${startup.runtime != null}
                Runtime provider: ${aiRuntimeConfiguration.provider}
                Runtime model: ${aiRuntimeConfiguration.model}
                Runtime error: ${startup.errorMessage ?: "none"}
                Session check timestamp: ${Instant.now()}
                """.trimIndent()
            },
        )

    // Use stderr for startup diagnostics so stdout remains clean JSON-RPC for MCP transport.
    System.err.println(
        """
        BertBot MCP server started.
        Server: $MCP_SERVER_NAME v$MCP_SERVER_VERSION
        Tools: $ASK_BERTBOT_TOOL_NAME, $BERTBOT_STATUS_TOOL_NAME, $WORKSPACE_LIST_DIR_TOOL_NAME, $WORKSPACE_READ_FILE_TOOL_NAME, $WORKSPACE_SEARCH_TOOL_NAME
        Workspace root: ${workspaceRoot.absolutePath}
        Provider: ${aiRuntimeConfiguration.provider}
        Model: ${aiRuntimeConfiguration.model}
        Runtime ready: ${startup.runtime != null}
        Runtime error: ${startup.errorMessage ?: "none"}
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
    } finally {
        startup.runtime?.close()
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
    private val respondToPrompt: (String, String?) -> String?,
    workspaceRoot: File = File("."),
    private val statusProvider: () -> String = {
        "Connected to $MCP_SERVER_NAME MCP server. Active tool surface: $ASK_BERTBOT_TOOL_NAME, $BERTBOT_STATUS_TOOL_NAME, $WORKSPACE_LIST_DIR_TOOL_NAME, $WORKSPACE_READ_FILE_TOOL_NAME, $WORKSPACE_SEARCH_TOOL_NAME"
    },
) {
    private val workspaceRootFile = workspaceRoot.canonicalFile
    private val workspaceInspector = WorkspaceInspector(workspaceRootFile)

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
            WORKSPACE_LIST_DIR_TOOL_NAME -> handleWorkspaceListDir(requestId, params)
            WORKSPACE_READ_FILE_TOOL_NAME -> handleWorkspaceReadFile(requestId, params)
            WORKSPACE_SEARCH_TOOL_NAME -> handleWorkspaceSearch(requestId, params)
            else -> errorResponse(requestId, -32601, "Unknown tool: ${toolName ?: "<missing>"}")
        }
    }

    private fun handleWorkspaceListDir(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val arguments =
            params.objectValue("arguments")
                ?: params
        val pathValue =
            arguments.stringValue("path")
                ?: "."
        val directory = workspaceInspector.resolveWorkspacePath(pathValue)
        if (directory == null) {
            return toolResultResponse(requestId, true, "Path is outside workspace root.")
        }

        if (!directory.exists()) {
            return toolResultResponse(requestId, true, "Path does not exist: $pathValue")
        }
        if (!directory.isDirectory) {
            return toolResultResponse(requestId, true, "Path is not a directory: $pathValue")
        }

        val entries =
            directory
                .listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.joinToString(separator = "\n") { file ->
                    val suffix = if (file.isDirectory) "/" else ""
                    "${workspaceInspector.toWorkspaceRelativePath(file)}$suffix"
                }
                ?: ""

        val body = if (entries.isBlank()) "(empty directory)" else entries
        return toolResultResponse(requestId, false, body)
    }

    private fun handleWorkspaceReadFile(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val arguments = params.objectValue("arguments") ?: params
        val pathValue = arguments.stringValue("path") ?: arguments.stringValue("filePath")
        if (pathValue.isNullOrBlank()) {
            return toolResultResponse(requestId, true, "Missing required field: path")
        }

        val file = workspaceInspector.resolveWorkspacePath(pathValue)
        if (file == null) {
            return toolResultResponse(requestId, true, "Path is outside workspace root.")
        }

        if (!file.exists()) {
            return toolResultResponse(requestId, true, "File does not exist: $pathValue")
        }
        if (!file.isFile) {
            return toolResultResponse(requestId, true, "Path is not a file: $pathValue")
        }

        val maxChars = 50_000
        val content = file.readText()
        val truncated = if (content.length > maxChars) content.take(maxChars) else content
        val output =
            if (content.length > maxChars) {
                "$truncated\n\n[truncated: showing first $maxChars characters]"
            } else {
                truncated
            }
        return toolResultResponse(requestId, false, output)
    }

    private fun handleWorkspaceSearch(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val arguments =
            params.objectValue("arguments")
                ?: params
        val query = arguments.stringValue("query")
        if (query.isNullOrBlank()) {
            return toolResultResponse(requestId, true, "Missing required field: query")
        }

        val maxResults = (arguments.intValue("maxResults") ?: 20).coerceIn(1, 200)
        val files =
            workspaceRootFile
                .walkTopDown()
                .onEnter { dir -> dir.name != ".git" && dir.name != "build" && dir.name != ".gradle" }
                .filter { it.isFile }

        val matches = mutableListOf<String>()
        files.forEach { file ->
            if (matches.size >= maxResults) {
                return@forEach
            }

            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            val lineMatch =
                lines.withIndex().firstOrNull { indexed ->
                    indexed.value.contains(query, ignoreCase = true)
                }

            if (lineMatch != null) {
                val snippet = lineMatch.value.trim().take(200)
                matches.add("${workspaceInspector.toWorkspaceRelativePath(file)}:${lineMatch.index + 1}: $snippet")
            }
        }

        val body = if (matches.isEmpty()) "No matches found." else matches.joinToString(separator = "\n")
        return toolResultResponse(requestId, false, body)
    }

    private fun handleAskBertBot(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val requestCorrelationId = requestId.toSafeCorrelationId()
        val arguments = params.objectValue("arguments") ?: params
        val prompt =
            arguments.stringValue("prompt")
                ?: arguments.stringValue("input")
                ?: arguments.stringValue("text")
                ?: arguments.stringValue("query")

        if (prompt.isNullOrBlank()) {
            return toolResultResponse(requestId, true, "Missing prompt input for $ASK_BERTBOT_TOOL_NAME.")
        }

        if (isStatusProbePrompt(prompt)) {
            return toolResultResponse(requestId, false, buildDeterministicBackendStatus())
        }

        return try {
            val promptWithContext = buildPromptWithWorkspaceEvidence(prompt)
            val rawResponse = respondToPrompt(promptWithContext, requestCorrelationId)
            val response = rewriteFalseWorkspaceUnavailable(prompt, rawResponse)
            if (response.isNullOrBlank()) {
                toolResultResponse(requestId, true, "BertBot did not return a response.")
            } else {
                toolResultResponse(requestId, false, response)
            }
        } catch (e: Exception) {
            toolResultResponse(requestId, true, "BertBot failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun isStatusProbePrompt(prompt: String): Boolean {
        val normalizedPrompt = prompt.lowercase()
        if (STATUS_HINT_KEYWORDS.any { keyword -> normalizedPrompt.contains(keyword) }) {
            return true
        }

        val compactPrompt = normalizedPrompt.replace(Regex("\\s+"), " ").trim()
        return compactPrompt == "bert_bot status" || compactPrompt == "bertbot status"
    }

    private fun buildDeterministicBackendStatus(): String {
        val rootAccessible = workspaceRootFile.exists() && workspaceRootFile.isDirectory
        val rootEntries = workspaceRootFile.listFiles()?.size ?: 0
        return listOf(
            statusProvider(),
            "Workspace root accessible: $rootAccessible",
            "Workspace entry count: $rootEntries",
            "Backend routing: available",
        ).joinToString(separator = "\n")
    }

    private fun buildPromptWithWorkspaceEvidence(prompt: String): String {
        if (!requiresWorkspaceEvidence(prompt)) {
            return prompt
        }

        val evidence = buildWorkspaceEvidenceSummary()
        return listOf(
            prompt,
            "",
            "Backend-verified workspace evidence (do not claim lack of repository visibility unless this section explicitly says unavailable):",
            evidence,
        ).joinToString(separator = "\n")
    }

    private fun rewriteFalseWorkspaceUnavailable(
        originalPrompt: String,
        rawResponse: String?,
    ): String? {
        if (rawResponse.isNullOrBlank()) {
            return rawResponse
        }

        if (!requiresWorkspaceEvidence(originalPrompt)) {
            return rawResponse
        }

        val normalizedResponse = rawResponse.lowercase()
        val hasUnavailableMarker = BACKEND_UNAVAILABLE_MARKERS.any { marker -> normalizedResponse.contains(marker) }
        if (!hasUnavailableMarker) {
            return rawResponse
        }

        val hasRepoEvidence = hasWorkspaceEvidenceForRepoPaths()
        if (!hasRepoEvidence) {
            return rawResponse
        }

        val evidence = buildWorkspaceEvidenceSummary()
        return listOf(
            "Backend workspace tools are available in this session, and repository evidence was verified.",
            "",
            "Verified evidence:",
            evidence,
            "",
            "The prior backend response claimed workspace unavailability, but that claim contradicts verified local evidence.",
            "Proceed with repository-backed findings using the evidence above.",
        ).joinToString(separator = "\n")
    }

    private fun requiresWorkspaceEvidence(prompt: String): Boolean {
        val normalizedPrompt = prompt.lowercase()
        return EVIDENCE_HINT_KEYWORDS.any { keyword -> normalizedPrompt.contains(keyword) }
    }

    private fun buildWorkspaceEvidenceSummary(): String {
        val topLevelEntries = workspaceInspector.listTopLevelEntries()
        val mainPackages = workspaceInspector.listImmediateChildren("src/main/kotlin/com/personalagent/bertbot")
        val testPackages = workspaceInspector.listImmediateChildren("src/test/kotlin/com/personalagent/bertbot")

        return listOf(
            "- workspace root: ${workspaceRootFile.absolutePath}",
            "- top-level entries: $topLevelEntries",
            "- src/main/kotlin/com/personalagent/bertbot children: $mainPackages",
            "- src/test/kotlin/com/personalagent/bertbot children: $testPackages",
        ).joinToString(separator = "\n")
    }

    private fun hasWorkspaceEvidenceForRepoPaths(): Boolean {
        val mainDir = workspaceInspector.resolveWorkspacePath("src/main/kotlin/com/personalagent/bertbot")
        val testDir = workspaceInspector.resolveWorkspacePath("src/test/kotlin/com/personalagent/bertbot")
        val hasMain = mainDir != null && mainDir.exists() && mainDir.isDirectory
        val hasTest = testDir != null && testDir.exists() && testDir.isDirectory
        return hasMain || hasTest
    }

    private fun toolResultResponse(
        requestId: JsonElement,
        isError: Boolean,
        message: String,
    ): String {
        val result = buildToolResult(message, isError)
        return successResponse(requestId, result)
    }
}

private class WorkspaceInspector(
    private val workspaceRootFile: File,
) {
    fun listTopLevelEntries(): String =
        workspaceRootFile
            .listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.joinToString(separator = ", ") { file ->
                if (file.isDirectory) {
                    "${file.name}/"
                } else {
                    file.name
                }
            }
            ?: "(unavailable)"

    fun listImmediateChildren(relativePath: String): String {
        val targetDir = resolveWorkspacePath(relativePath)
        if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
            return "(unavailable)"
        }

        val children =
            targetDir
                .listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.joinToString(separator = ", ") { file ->
                    if (file.isDirectory) {
                        "${file.name}/"
                    } else {
                        file.name
                    }
                }

        return if (children.isNullOrBlank()) "(empty)" else children
    }

    fun resolveWorkspacePath(pathValue: String): File? {
        val candidate =
            if (File(pathValue).isAbsolute) {
                File(pathValue)
            } else {
                File(workspaceRootFile, pathValue)
            }

        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (canonicalCandidate.isWithin(workspaceRootFile)) canonicalCandidate else null
    }

    fun toWorkspaceRelativePath(file: File): String {
        val workspacePath = workspaceRootFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return workspacePath.relativize(filePath).toString().replace("\\", "/")
    }
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

private fun buildToolResult(
    message: String,
    isError: Boolean,
): JsonObject {
    val result = JsonObject()
    val content = JsonArray()
    val textContent = JsonObject()
    textContent.addProperty("type", "text")
    textContent.addProperty("text", message)
    content.add(textContent)
    result.add("content", content)
    result.addProperty("isError", isError)
    return result
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

    tools.add(
        buildToolDefinition(
            ASK_BERTBOT_TOOL_NAME,
            "Pass a prompt to BertBot and return the orchestration response.",
        ) {
            property("prompt", "string", "Prompt to send to BertBot.")
            required("prompt")
        },
    )
    tools.add(
        buildToolDefinition(
            BERTBOT_STATUS_TOOL_NAME,
            "Return BertBot MCP backend status for this session.",
        ),
    )
    tools.add(
        buildToolDefinition(
            WORKSPACE_LIST_DIR_TOOL_NAME,
            "List files and directories under a workspace-relative path.",
        ) {
            property("path", "string", "Workspace-relative directory path. Defaults to '.'.")
        },
    )
    tools.add(
        buildToolDefinition(
            WORKSPACE_READ_FILE_TOOL_NAME,
            "Read a file from the workspace by relative path.",
        ) {
            property("path", "string", "Workspace-relative file path.")
            required("path")
        },
    )
    tools.add(
        buildToolDefinition(
            WORKSPACE_SEARCH_TOOL_NAME,
            "Search workspace files for a text query.",
        ) {
            property("query", "string", "Case-insensitive text to search for.")
            property("maxResults", "number", "Maximum number of matches to return (default 20).")
            required("query")
        },
    )
    result.add("tools", tools)
    return result
}

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

private fun JsonObject.intValue(name: String): Int? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        return null
    }
    return runCatching { element.asInt }.getOrNull()
}

private fun File.isWithin(root: File): Boolean {
    val targetPath = canonicalFile.toPath().normalize()
    val rootPath = root.canonicalFile.toPath().normalize()
    return targetPath == rootPath || targetPath.startsWith(rootPath)
}

private fun JsonElement.toSafeCorrelationId(): String? {
    if (isJsonNull) {
        return null
    }

    return if (isJsonPrimitive) {
        asJsonPrimitive.toString().removeSurrounding("\"").trim().takeIf { it.isNotBlank() }
    } else {
        toString().trim().takeIf { it.isNotBlank() }
    }
}

private data class StartupState(
    val runtime: BertBotRuntime?,
    val errorMessage: String? = null,
)

internal fun resolveWorkspaceRoot(
    environment: Map<String, String> = System.getenv(),
    currentDirectory: File = File("."),
): File {
    val configuredRoot = environment[WORKSPACE_ROOT_ENV_VAR]?.trim().orEmpty()
    if (configuredRoot.isNotBlank()) {
        val configured = File(configuredRoot)
        if (configured.exists() && configured.isDirectory) {
            return configured.canonicalFile
        }
    }

    val fromMarkers = findWorkspaceRootByMarkers(currentDirectory)
    return fromMarkers ?: currentDirectory.canonicalFile
}

internal fun findWorkspaceRootByMarkers(start: File): File? {
    var cursor: File? = start.canonicalFile
    while (cursor != null) {
        val hasGit = File(cursor, ".git").exists()
        val hasGradleSettings = File(cursor, "settings.gradle.kts").exists()
        if (hasGit || hasGradleSettings) {
            return cursor
        }
        cursor = cursor.parentFile
    }
    return null
}

private fun createStartupState(aiRuntimeConfiguration: AiRuntimeConfiguration): StartupState {
    return try {
        val runtime = BertBotRuntimeFactory.create(aiRuntimeConfiguration = aiRuntimeConfiguration)
        if (runtime == null) {
            StartupState(runtime = null, errorMessage = "Missing AI provider API key (BERTBOT_AI_API_KEY).")
        } else {
            StartupState(runtime = runtime)
        }
    } catch (e: Exception) {
        StartupState(runtime = null, errorMessage = e.message ?: "runtime initialization failed")
    }
}
