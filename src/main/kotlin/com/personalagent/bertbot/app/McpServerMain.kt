package com.personalagent.bertbot.app

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import java.io.File

fun main() {
    val aiRuntimeConfiguration = resolveAiRuntimeConfiguration()
    val macrofactorRuntimeConfiguration = resolveMacrofactorRuntimeConfiguration()
    val workspaceRoot = resolveWorkspaceRoot()
    val dispatcherContext =
        McpServerBootstrap.createDispatcherContext(
            McpServerBootstrap.DispatcherContextInput(
                aiRuntimeConfiguration = aiRuntimeConfiguration,
                macrofactorRuntimeConfiguration = macrofactorRuntimeConfiguration,
                workspaceRoot = workspaceRoot,
                toolNames = McpConstants.toolNames,
            ),
        )
    val startup = dispatcherContext.startup
    val dispatcher = dispatcherContext.dispatcher

    logMcpStartupDiagnostics(
        McpStartupDiagnostics(
            serverName = McpConstants.SERVER_NAME,
            serverVersion = McpConstants.SERVER_VERSION,
            tools = McpConstants.startupTools,
            workspaceRootPath = workspaceRoot.absolutePath,
            provider = aiRuntimeConfiguration.provider,
            model = aiRuntimeConfiguration.model,
            macrofactorEnabled = macrofactorRuntimeConfiguration.enabled,
            macrofactorConfigured = macrofactorRuntimeConfiguration.isConfigured,
            runtimeReady = startup.runtime != null,
            runtimeError = startup.errorMessage ?: "none",
        ),
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

@Suppress("LongParameterList")
internal class McpRequestDispatcher(
    private val respondToPrompt: (String, String?) -> String?,
    workspaceRoot: File = File("."),
    private val macrofactorToolRouter: MacrofactorToolRouter? = null,
    private val polymarketToolRouter: PolymarketToolRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment()),
    private val continuousResearchToolRouter: ContinuousResearchToolRouter? = null,
    private val ingestionControlPlane: IngestionControlPlane? = null,
    private val externalChatResponder: ((NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome)? = null,
    private val statusProvider: () -> String = {
        "Connected to ${McpConstants.SERVER_NAME} MCP server. Active tool surface: ${McpConstants.defaultStatusToolSurface.joinToString()}"
    },
) {
    private val workspaceRootFile = workspaceRoot.canonicalFile
    private val workspaceToolHandler = McpWorkspaceToolHandler(workspaceRootFile)
    private val ingestionToolHandler = McpIngestionToolHandler(ingestionControlPlane, externalChatResponder)
    private val askBertBotToolHandler =
        McpAskBertBotToolHandler(
            workspaceRoot = workspaceRootFile,
            respondToPrompt = respondToPrompt,
            statusProvider = statusProvider,
            backendUnavailableMarkers = McpPromptHeuristics.backendUnavailableMarkers,
            evidenceHintKeywords = McpPromptHeuristics.evidenceHintKeywords,
            statusHintKeywords = McpPromptHeuristics.statusHintKeywords,
        )

    fun handle(rawMessage: String): String? {
        val request = McpProtocolCodec.parseRequest(rawMessage) ?: return McpProtocolCodec.errorResponse(null, -32700, "Invalid JSON")
        val requestId = request.id

        if (requestId == null) {
            return null
        }

        return when (request.method) {
            "initialize" ->
                McpProtocolCodec.successResponse(
                    requestId,
                    buildInitializeResultPayload(
                        protocolVersion = McpConstants.PROTOCOL_VERSION,
                        serverName = McpConstants.SERVER_NAME,
                        serverVersion = McpConstants.SERVER_VERSION,
                    ),
                )
            "initialized" -> null
            "ping" -> McpProtocolCodec.successResponse(requestId, JsonObject())
            "tools/list" ->
                McpProtocolCodec.successResponse(
                    requestId,
                    buildToolsListResultPayload(
                        includeIngestionTools = ingestionControlPlane != null,
                        toolNames = McpConstants.toolNames,
                        macrofactorToolDefinitions = macrofactorToolRouter?.toolDefinitions() ?: emptyList(),
                        continuousResearchToolDefinitions = continuousResearchToolRouter?.toolDefinitions() ?: emptyList(),
                    ),
                )
            "tools/call" -> handleToolCall(requestId, request.params)
            else -> McpProtocolCodec.errorResponse(requestId, -32601, "Method not found: ${request.method}")
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleToolCall(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val toolName = params.stringValue("name") ?: params.stringValue("toolName")
        val macrofactorResult = macrofactorToolRouter?.handle(toolName, params)
        if (macrofactorResult != null) {
            return toolResultResponse(requestId, macrofactorResult.first, macrofactorResult.second)
        }
        val continuousResearchResult = continuousResearchToolRouter?.handle(toolName, params)
        if (continuousResearchResult != null) {
            return toolResultResponse(requestId, continuousResearchResult.first, continuousResearchResult.second)
        }

        return when (toolName) {
            McpConstants.ASK_BERTBOT_TOOL_NAME ->
                toolResultResponseFromOutcome(
                    requestId,
                    askBertBotToolHandler.handle(params, McpRequestId.toSafeCorrelationId(requestId)),
                )
            McpConstants.BERTBOT_STATUS_TOOL_NAME -> toolResultResponse(requestId, false, statusProvider())
            McpConstants.WORKSPACE_LIST_DIR_TOOL_NAME -> toolResultResponseFromOutcome(requestId, workspaceToolHandler.listDir(params))
            McpConstants.WORKSPACE_READ_FILE_TOOL_NAME -> toolResultResponseFromOutcome(requestId, workspaceToolHandler.readFile(params))
            McpConstants.WORKSPACE_SEARCH_TOOL_NAME -> toolResultResponseFromOutcome(requestId, workspaceToolHandler.search(params))
            McpConstants.POLYMARKET_GAMMA_TOOL_NAME,
            McpConstants.POLYMARKET_CLOB_TOOL_NAME,
            McpConstants.POLYMARKET_DATA_TOOL_NAME,
            -> handlePolymarketToolCall(requestId, params)
            McpConstants.INGESTION_SET_APPROVAL_TOOL_NAME -> toolResultResponseFromOutcome(requestId, ingestionToolHandler.setApproval(params))
            McpConstants.INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME -> toolResultResponseFromOutcome(requestId, ingestionToolHandler.listApprovedSources())
            McpConstants.INGESTION_INGEST_MANUAL_TOOL_NAME -> toolResultResponseFromOutcome(requestId, ingestionToolHandler.manualIngest(params))
            McpConstants.INGESTION_CHAT_MANUAL_TOOL_NAME -> toolResultResponseFromOutcome(requestId, ingestionToolHandler.manualChat(params))
            else -> McpProtocolCodec.errorResponse(requestId, -32601, "Unknown tool: ${toolName ?: "<missing>"}")
        }
    }

    private fun toolResultResponseFromOutcome(
        requestId: JsonElement,
        outcome: Pair<Boolean, String>,
    ): String = toolResultResponse(requestId, outcome.first, outcome.second)

    private fun handlePolymarketToolCall(
        requestId: JsonElement,
        params: JsonObject,
    ): String {
        val toolName = params.stringValue("name") ?: params.stringValue("toolName") ?: return toolResultResponse(requestId, true, "Missing tool name")
        val outcome = polymarketToolRouter.handle(toolName, params) ?: return toolResultResponse(requestId, true, "Unsupported Polymarket tool: $toolName")
        return toolResultResponse(requestId, outcome.first, outcome.second)
    }

    private fun toolResultResponse(
        requestId: JsonElement,
        isError: Boolean,
        message: String,
    ): String {
        val result = McpProtocolCodec.toolResult(message, isError)
        return McpProtocolCodec.successResponse(requestId, result)
    }
}

internal data class McpStartupState(
    val runtime: BertBotRuntime?,
    val errorMessage: String? = null,
)
