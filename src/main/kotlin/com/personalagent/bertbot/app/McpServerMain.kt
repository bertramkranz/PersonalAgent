package com.personalagent.bertbot.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.ingestion.ApprovalScope
import com.personalagent.bertbot.ingestion.ApprovalUpdateRequest
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
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
private const val INGESTION_SET_APPROVAL_TOOL_NAME = "ingestion_set_approval"
private const val INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME = "ingestion_list_approved_sources"
private const val INGESTION_INGEST_MANUAL_TOOL_NAME = "ingestion_ingest_manual"
private const val INGESTION_CHAT_MANUAL_TOOL_NAME = "ingestion_chat_manual"
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
    val macrofactorRuntimeConfiguration = resolveMacrofactorRuntimeConfiguration()
    val startup = createStartupState(aiRuntimeConfiguration)
    val workspaceRoot = resolveWorkspaceRoot()
    val macrofactorToolRouter =
        if (macrofactorRuntimeConfiguration.enabled) {
            MacrofactorToolRouter(macrofactorRuntimeConfiguration)
        } else {
            null
        }

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
            macrofactorToolRouter = macrofactorToolRouter,
            ingestionControlPlane = startup.runtime?.ingestionControlPlane(),
            externalChatResponder = startup.runtime?.let { runtime -> { message, dryRun -> runtime.chatFromExternalMessage(message, dryRun) } },
            statusProvider =
                createStatusProvider(
                    startup = startup,
                    workspaceRoot = workspaceRoot,
                    aiRuntimeConfiguration = aiRuntimeConfiguration,
                    macrofactorToolRouter = macrofactorToolRouter,
                ),
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
        MacroFactor enabled: ${macrofactorRuntimeConfiguration.enabled}
        MacroFactor configured: ${macrofactorRuntimeConfiguration.isConfigured}
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

private fun createStatusProvider(
    startup: StartupState,
    workspaceRoot: File,
    aiRuntimeConfiguration: AiRuntimeConfiguration,
    macrofactorToolRouter: MacrofactorToolRouter?,
): () -> String {
    val macrofactorToolNames =
        macrofactorToolRouter
            ?.toolDefinitions()
            ?.mapNotNull { it.get("name")?.asString?.takeIf { name -> name.isNotBlank() } }
            ?: emptyList()
    return {
        val baseTools =
            mutableListOf(
                ASK_BERTBOT_TOOL_NAME,
                BERTBOT_STATUS_TOOL_NAME,
                WORKSPACE_LIST_DIR_TOOL_NAME,
                WORKSPACE_READ_FILE_TOOL_NAME,
                WORKSPACE_SEARCH_TOOL_NAME,
            )
        if (startup.runtime?.ingestionControlPlane() != null) {
            baseTools += INGESTION_SET_APPROVAL_TOOL_NAME
            baseTools += INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME
            baseTools += INGESTION_INGEST_MANUAL_TOOL_NAME
            baseTools += INGESTION_CHAT_MANUAL_TOOL_NAME
        }
        macrofactorToolNames.forEach { name -> baseTools += name }
        """
        Connected to $MCP_SERVER_NAME MCP server.
        Active tool surface: ${baseTools.joinToString()}
        Workspace root: ${workspaceRoot.absolutePath}
        Runtime ready: ${startup.runtime != null}
        Runtime provider: ${aiRuntimeConfiguration.provider}
        Runtime model: ${aiRuntimeConfiguration.model}
        Runtime error: ${startup.errorMessage ?: "none"}
        Session check timestamp: ${Instant.now()}
        """.trimIndent()
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
    private val macrofactorToolRouter: MacrofactorToolRouter? = null,
    private val ingestionControlPlane: IngestionControlPlane? = null,
    private val externalChatResponder: ((NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome)? = null,
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
            "tools/list" ->
                successResponse(
                    requestId,
                    buildToolsListResult(
                        includeIngestionTools = ingestionControlPlane != null,
                        macrofactorToolDefinitions = macrofactorToolRouter?.toolDefinitions() ?: emptyList(),
                    ),
                )
            "tools/call" -> handleToolCall(requestId, request.params)
            else -> errorResponse(requestId, -32601, "Method not found: ${request.method}")
        }
    }

    private fun handleToolCall(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val toolName = params.stringValue("name") ?: params.stringValue("toolName")
        val macrofactorResult = macrofactorToolRouter?.handle(toolName, params)
        if (macrofactorResult != null) {
            return toolResultResponse(requestId, macrofactorResult.first, macrofactorResult.second)
        }

        return when (toolName) {
            ASK_BERTBOT_TOOL_NAME -> handleAskBertBot(requestId, params)
            BERTBOT_STATUS_TOOL_NAME -> toolResultResponse(requestId, false, statusProvider())
            WORKSPACE_LIST_DIR_TOOL_NAME -> handleWorkspaceListDir(requestId, params)
            WORKSPACE_READ_FILE_TOOL_NAME -> handleWorkspaceReadFile(requestId, params)
            WORKSPACE_SEARCH_TOOL_NAME -> handleWorkspaceSearch(requestId, params)
            INGESTION_SET_APPROVAL_TOOL_NAME -> handleIngestionSetApproval(requestId, params)
            INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME -> handleIngestionListApprovedSources(requestId)
            INGESTION_INGEST_MANUAL_TOOL_NAME -> handleIngestionManualIngest(requestId, params)
            INGESTION_CHAT_MANUAL_TOOL_NAME -> handleIngestionManualChat(requestId, params)
            else -> errorResponse(requestId, -32601, "Unknown tool: ${toolName ?: "<missing>"}")
        }
    }

    private fun handleIngestionSetApproval(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val control = ingestionControlPlane ?: return toolResultResponse(requestId, true, "Ingestion control is disabled.")
        val arguments = params.objectValue("arguments") ?: params
        val source = arguments.toSource() ?: return toolResultResponse(requestId, true, "Missing or invalid source parameters.")
        val approved = arguments.booleanValue("approved") ?: return toolResultResponse(requestId, true, "Missing required field: approved")
        val scope = arguments.stringValue("scope")?.toApprovalScope() ?: ApprovalScope.CHAT

        val record = control.setApproval(ApprovalUpdateRequest(source = source, scope = scope, approved = approved))
        val status = if (record.approved) "approved" else "revoked"
        return toolResultResponse(
            requestId,
            false,
            "${record.source.platform.name.lowercase()}:${record.source.sourceId} $status at scope ${record.scope.name.lowercase()}.",
        )
    }

    private fun handleIngestionListApprovedSources(requestId: JsonElement): String {
        val control = ingestionControlPlane ?: return toolResultResponse(requestId, true, "Ingestion control is disabled.")
        val approved = control.listApprovedSources()
        if (approved.isEmpty()) {
            return toolResultResponse(requestId, false, "No approved ingestion sources.")
        }

        val lines =
            approved.joinToString(separator = "\n") { record ->
                "${record.source.platform.name.lowercase()} ${record.source.sourceKind.name.lowercase()} ${record.source.sourceId} scope=${record.scope.name.lowercase()}"
            }
        return toolResultResponse(requestId, false, lines)
    }

    private fun handleIngestionManualIngest(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val control = ingestionControlPlane ?: return toolResultResponse(requestId, true, "Ingestion control is disabled.")
        val arguments = params.objectValue("arguments") ?: params
        val source = arguments.toSource() ?: return toolResultResponse(requestId, true, "Missing or invalid source parameters.")
        val text = arguments.stringValue("text")
        val messageId = arguments.stringValue("messageId") ?: "manual-${System.currentTimeMillis()}"
        val dryRun = arguments.booleanValue("dryRun") ?: true
        val senderId = arguments.stringValue("senderId")
        val senderDisplayName = arguments.stringValue("senderDisplayName")
        val threadId = arguments.stringValue("threadId")
        val occurredAt = arguments.stringValue("occurredAt") ?: Instant.now().toString()

        val outcome =
            control.ingestManual(
                messages =
                    listOf(
                        NormalizedIngestionMessage(
                            messageId = messageId,
                            source = source,
                            senderId = senderId,
                            senderDisplayName = senderDisplayName,
                            text = text,
                            threadId = threadId,
                            occurredAt = occurredAt,
                        ),
                    ),
                dryRun = dryRun,
            ).firstOrNull()

        if (outcome == null) {
            return toolResultResponse(requestId, true, "Ingestion returned no outcome.")
        }

        return toolResultResponse(
            requestId,
            false,
            "decision=${outcome.decision.name.lowercase()} dryRun=${outcome.dryRun} attachments=${outcome.attachmentRecords.size}",
        )
    }

    private fun handleIngestionManualChat(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val responder = externalChatResponder ?: return toolResultResponse(requestId, true, "External chat bridge is disabled.")
        val arguments = params.objectValue("arguments") ?: params
        val source = arguments.toSource() ?: return toolResultResponse(requestId, true, "Missing or invalid source parameters.")
        val text = arguments.stringValue("text")
        if (text.isNullOrBlank()) {
            return toolResultResponse(requestId, true, "Missing required field: text")
        }

        val message =
            NormalizedIngestionMessage(
                messageId = arguments.stringValue("messageId") ?: "manual-${System.currentTimeMillis()}",
                source = source,
                senderId = arguments.stringValue("senderId"),
                senderDisplayName = arguments.stringValue("senderDisplayName"),
                text = text,
                threadId = arguments.stringValue("threadId"),
                occurredAt = arguments.stringValue("occurredAt") ?: Instant.now().toString(),
            )

        val dryRun = arguments.booleanValue("dryRun") ?: false
        val outcome = responder(message, dryRun)
        val reply = outcome.outbound?.text ?: ""
        val replySummary = if (reply.isBlank()) "(no reply generated)" else reply
        return toolResultResponse(
            requestId,
            false,
            "decision=${outcome.ingestion.decision.name.lowercase()} dryRun=${outcome.dryRun}\nreply=$replySummary",
        )
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

            var lineNumber = 0
            var matched = false
            runCatching {
                file.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        lineNumber++
                        if (!matched && line.contains(query, ignoreCase = true)) {
                            matched = true
                            val snippet = line.trim().take(200)
                            matches.add("${workspaceInspector.toWorkspaceRelativePath(file)}:$lineNumber: $snippet")
                        }
                    }
                }
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

private fun buildToolsListResult(
    includeIngestionTools: Boolean,
    macrofactorToolDefinitions: List<JsonObject> = emptyList(),
): JsonObject {
    val result = JsonObject()
    val tools = JsonArray()
    baseToolDefinitions().forEach { tool -> tools.add(tool) }
    if (includeIngestionTools) {
        ingestionToolDefinitions().forEach { tool -> tools.add(tool) }
    }

    macrofactorToolDefinitions.forEach { tool -> tools.add(tool) }

    result.add("tools", tools)
    return result
}

private fun baseToolDefinitions(): List<JsonObject> =
    listOf(
        buildToolDefinition(
            ASK_BERTBOT_TOOL_NAME,
            "Pass a prompt to BertBot and return the orchestration response.",
        ) {
            property("prompt", "string", "Prompt to send to BertBot.")
            required("prompt")
        },
        buildToolDefinition(
            BERTBOT_STATUS_TOOL_NAME,
            "Return BertBot MCP backend status for this session.",
        ),
        buildToolDefinition(
            WORKSPACE_LIST_DIR_TOOL_NAME,
            "List files and directories under a workspace-relative path.",
        ) {
            property("path", "string", "Workspace-relative directory path. Defaults to '.'.")
        },
        buildToolDefinition(
            WORKSPACE_READ_FILE_TOOL_NAME,
            "Read a file from the workspace by relative path.",
        ) {
            property("path", "string", "Workspace-relative file path.")
            required("path")
        },
        buildToolDefinition(
            WORKSPACE_SEARCH_TOOL_NAME,
            "Search workspace files for a text query.",
        ) {
            property("query", "string", "Case-insensitive text to search for.")
            property("maxResults", "number", "Maximum number of matches to return (default 20).")
            required("query")
        },
    )

private fun ingestionToolDefinitions(): List<JsonObject> =
    listOf(
        buildToolDefinition(
            INGESTION_SET_APPROVAL_TOOL_NAME,
            "Set or revoke approval for a specific external source.",
        ) {
            property("platform", "string", "Source platform (telegram, slack, whatsapp, manual).")
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
            INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME,
            "List currently approved external ingestion sources.",
        ),
        buildToolDefinition(
            INGESTION_INGEST_MANUAL_TOOL_NAME,
            "Manually ingest or dry-run one normalized message payload.",
        ) {
            property("platform", "string", "Source platform (telegram, slack, whatsapp, manual).")
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
            INGESTION_CHAT_MANUAL_TOOL_NAME,
            "Route one approved external message through BertBot and return the reply payload.",
        ) {
            property("platform", "string", "Source platform (telegram, slack, whatsapp, manual).")
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

private fun JsonObject.booleanValue(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        return null
    }
    return runCatching { element.asBoolean }.getOrNull()
}

private fun JsonObject.toSource(): IngestionSource? {
    val platformValue = stringValue("platform") ?: return null
    val sourceKindValue = stringValue("sourceKind") ?: return null
    val sourceIdValue = stringValue("sourceId") ?: return null

    val platform = platformValue.toIngestionPlatform() ?: return null
    val sourceKind = sourceKindValue.toSourceKind() ?: return null

    return IngestionSource(
        platform = platform,
        sourceKind = sourceKind,
        sourceId = sourceIdValue,
        workspaceId = stringValue("workspaceId"),
    )
}

private fun String.toIngestionPlatform(): IngestionPlatform? =
    when (lowercase()) {
        "telegram" -> IngestionPlatform.TELEGRAM
        "slack" -> IngestionPlatform.SLACK
        "whatsapp" -> IngestionPlatform.WHATSAPP
        "manual" -> IngestionPlatform.MANUAL
        else -> null
    }

private fun String.toSourceKind(): IngestionSourceKind? =
    when (lowercase()) {
        "chat" -> IngestionSourceKind.CHAT
        "channel" -> IngestionSourceKind.CHANNEL
        "direct_message" -> IngestionSourceKind.DIRECT_MESSAGE
        "business_conversation" -> IngestionSourceKind.BUSINESS_CONVERSATION
        else -> null
    }

private fun String.toApprovalScope(): ApprovalScope? =
    when (lowercase()) {
        "chat" -> ApprovalScope.CHAT
        "channel" -> ApprovalScope.CHANNEL
        "conversation" -> ApprovalScope.CONVERSATION
        "user" -> ApprovalScope.USER
        else -> null
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
