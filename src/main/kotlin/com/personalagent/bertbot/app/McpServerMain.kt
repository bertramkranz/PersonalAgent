package com.personalagent.bertbot.app

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import java.io.File

fun main() {
    val aiRuntimeConfiguration = resolveAiRuntimeConfiguration()
    val macrofactorRuntimeConfiguration = resolveMacrofactorRuntimeConfiguration()
    val googleWorkspaceRuntimeConfiguration = resolveGoogleWorkspaceRuntimeConfiguration()
    val shoppingRuntimeConfiguration = resolveShoppingRuntimeConfiguration()
    val workspaceRoot = resolveWorkspaceRoot()
    val dispatcherContext =
        McpServerBootstrap.createDispatcherContext(
            McpServerBootstrap.DispatcherContextInput(
                aiRuntimeConfiguration = aiRuntimeConfiguration,
                macrofactorRuntimeConfiguration = macrofactorRuntimeConfiguration,
                googleWorkspaceRuntimeConfiguration = googleWorkspaceRuntimeConfiguration,
                shoppingRuntimeConfiguration = shoppingRuntimeConfiguration,
                workspaceRoot = workspaceRoot,
                toolNames = McpConstants.toolNames,
            ),
        )
    val startup = dispatcherContext.startup
    val dispatcher = dispatcherContext.dispatcher
    val macrofactorStatus = summarizeMacrofactorAvailability(macrofactorRuntimeConfiguration, dispatcherContext.macrofactorToolRouter)
    val googleWorkspaceStatus = summarizeGoogleWorkspaceAvailability(googleWorkspaceRuntimeConfiguration, dispatcherContext.googleWorkspaceToolRouter)

    logMcpStartupDiagnostics(
        McpStartupDiagnostics(
            serverName = McpConstants.SERVER_NAME,
            serverVersion = McpConstants.SERVER_VERSION,
            tools = McpConstants.startupTools,
            workspaceRootPath = workspaceRoot.absolutePath,
            provider = aiRuntimeConfiguration.provider,
            model = aiRuntimeConfiguration.model,
            macrofactorStatus = macrofactorStatus,
            googleWorkspaceStatus = googleWorkspaceStatus,
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
    persistenceConfiguration: PersistenceRuntimeConfiguration = resolvePersistenceRuntimeConfiguration(),
    private val macrofactorToolRouter: MacrofactorToolRouter? = null,
    private val googleWorkspaceToolRouter: GoogleWorkspaceToolRouter? = null,
    private val polymarketToolRouter: PolymarketToolRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment()),
    private val continuousResearchToolRouter: ContinuousResearchToolRouter? = null,
    private val shoppingToolRouter: ShoppingToolRouter? = null,
    private val ingestionControlPlane: IngestionControlPlane? = null,
    private val externalChatResponder: ((NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome)? = null,
    private val listCheckpoints: ((scopeKey: String?) -> List<BertBotCheckpoint>)? = null,
    private val latestCheckpoint: ((scopeKey: String?) -> BertBotCheckpoint?)? = null,
    private val checkpointById: ((checkpointId: String, scopeKey: String?) -> BertBotCheckpoint?)? = null,
    private val rollbackToCheckpoint: ((checkpointId: String, scopeKey: String?) -> BertBotState)? = null,
    private val checkpointRollbackPolicy: CheckpointRollbackPolicyConfiguration = CheckpointRollbackPolicyConfiguration(),
    capabilityRegistry: CapabilityRegistry? = null,
    private val statusProvider: () -> String = {
        "Connected to ${McpConstants.SERVER_NAME} MCP server. Active tool surface: ${McpConstants.defaultStatusToolSurface.joinToString()}"
    },
) {
    private val workspaceRootFile = workspaceRoot.canonicalFile
    private val workspaceToolHandler = McpWorkspaceToolHandler(workspaceRootFile, persistenceConfiguration)
    private val ingestionToolHandler = McpIngestionToolHandler(ingestionControlPlane, externalChatResponder)
    private val checkpointToolHandler =
        if (hasCheckpointToolFunctions()) {
            McpCheckpointToolHandler(
                listCheckpoints = requireNotNull(listCheckpoints),
                latestCheckpoint = requireNotNull(latestCheckpoint),
                checkpointById = requireNotNull(checkpointById),
                rollbackToCheckpoint = requireNotNull(rollbackToCheckpoint),
                rollbackPolicy = checkpointRollbackPolicy,
            )
        } else {
            null
        }
    private val askBertBotToolHandler =
        McpAskBertBotToolHandler(
            workspaceRoot = workspaceRootFile,
            respondToPrompt = respondToPrompt,
            statusProvider = statusProvider,
            backendUnavailableMarkers = McpPromptHeuristics.backendUnavailableMarkers,
            evidenceHintKeywords = McpPromptHeuristics.evidenceHintKeywords,
            statusHintKeywords = McpPromptHeuristics.statusHintKeywords,
        )
    private val dispatcherCapabilityRegistry =
        capabilityRegistry ?: buildDispatcherCapabilityRegistry()
    private val toolBus =
        UnifiedToolBus(
            capabilityRegistry = dispatcherCapabilityRegistry,
            builtInHandlers =
                mapOf(
                    McpConstants.ASK_BERTBOT_TOOL_NAME to
                        { params ->
                            askBertBotToolHandler.handle(params, null)
                        },
                    McpConstants.BERTBOT_STATUS_TOOL_NAME to
                        { _ ->
                            false to statusProvider()
                        },
                    McpConstants.WORKSPACE_LIST_DIR_TOOL_NAME to
                        { params ->
                            workspaceToolHandler.listDir(params)
                        },
                    McpConstants.WORKSPACE_READ_FILE_TOOL_NAME to
                        { params ->
                            workspaceToolHandler.readFile(params)
                        },
                    McpConstants.WORKSPACE_SEARCH_TOOL_NAME to
                        { params ->
                            workspaceToolHandler.search(params)
                        },
                    McpConstants.INGESTION_SET_APPROVAL_TOOL_NAME to
                        { params ->
                            ingestionToolHandler.setApproval(params)
                        },
                    McpConstants.INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME to
                        { _ ->
                            ingestionToolHandler.listApprovedSources()
                        },
                    McpConstants.INGESTION_INGEST_MANUAL_TOOL_NAME to
                        { params ->
                            ingestionToolHandler.manualIngest(params)
                        },
                    McpConstants.INGESTION_CHAT_MANUAL_TOOL_NAME to
                        { params ->
                            ingestionToolHandler.manualChat(params)
                        },
                    McpConstants.CHECKPOINT_LIST_TOOL_NAME to
                        { params ->
                            executeCheckpointTool(McpConstants.CHECKPOINT_LIST_TOOL_NAME, params)
                        },
                    McpConstants.CHECKPOINT_LATEST_TOOL_NAME to
                        { params ->
                            executeCheckpointTool(McpConstants.CHECKPOINT_LATEST_TOOL_NAME, params)
                        },
                    McpConstants.CHECKPOINT_GET_TOOL_NAME to
                        { params ->
                            executeCheckpointTool(McpConstants.CHECKPOINT_GET_TOOL_NAME, params)
                        },
                    McpConstants.CHECKPOINT_ROLLBACK_TOOL_NAME to
                        { params ->
                            executeCheckpointTool(McpConstants.CHECKPOINT_ROLLBACK_TOOL_NAME, params)
                        },
                    McpConstants.CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME to
                        { params ->
                            executeCheckpointTool(McpConstants.CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME, params)
                        },
                    McpConstants.CHECKPOINT_POLICY_TOOL_NAME to
                        { params ->
                            executeCheckpointTool(McpConstants.CHECKPOINT_POLICY_TOOL_NAME, params)
                        },
                ),
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
                        optionalToolDefinitions =
                            OptionalToolDefinitions(
                                macrofactorToolDefinitions = toolBus.toolDefinitionsFor("macrofactor"),
                                googleWorkspaceToolDefinitions = toolBus.toolDefinitionsFor("google_workspace"),
                                continuousResearchToolDefinitions = toolBus.toolDefinitionsFor("continuous_research"),
                                shoppingToolDefinitions = toolBus.toolDefinitionsFor("shopping"),
                            ),
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
        if (toolName.isNullOrBlank()) {
            return McpProtocolCodec.errorResponse(requestId, -32602, "Missing tool name")
        }

        if (toolName == McpConstants.ASK_BERTBOT_TOOL_NAME) {
            val correlationId = McpRequestId.toSafeCorrelationId(requestId)
            val outcome = askBertBotToolHandler.handle(params, correlationId)
            return toolResultResponse(requestId, outcome.first, outcome.second)
        }

        val outcome = toolBus.execute(toolName, params)
        if (outcome != null) {
            return toolResultResponse(requestId, outcome.first, outcome.second)
        }

        return McpProtocolCodec.errorResponse(requestId, -32601, "Unknown tool: $toolName")
    }

    private fun buildDispatcherCapabilityRegistry(): CapabilityRegistry {
        val capabilities = mutableListOf<CapabilityDefinition>()

        if (macrofactorToolRouter != null) {
            capabilities +=
                CapabilityDefinition(
                    id = "macrofactor",
                    router =
                        FunctionToolRouter(
                            id = "macrofactor",
                            definitionsProvider = macrofactorToolRouter::toolDefinitions,
                            executor = { toolName, callParams -> macrofactorToolRouter.handle(toolName, callParams) },
                        ),
                )
        }

        if (googleWorkspaceToolRouter != null) {
            capabilities +=
                CapabilityDefinition(
                    id = "google_workspace",
                    router =
                        FunctionToolRouter(
                            id = "google_workspace",
                            definitionsProvider = googleWorkspaceToolRouter::toolDefinitions,
                            executor = { toolName, callParams -> googleWorkspaceToolRouter.handle(toolName, callParams) },
                        ),
                )
        }

        capabilities +=
            CapabilityDefinition(
                id = "polymarket",
                router =
                    FunctionToolRouter(
                        id = "polymarket",
                        definitionsProvider = { polymarketToolDefinitions(polymarketToolRouter) },
                        executor = { toolName, callParams ->
                            if (!isPolymarketToolName(toolName)) {
                                null
                            } else {
                                polymarketToolRouter.handle(toolName.orEmpty(), callParams)
                            }
                        },
                    ),
            )

        if (continuousResearchToolRouter != null) {
            capabilities +=
                CapabilityDefinition(
                    id = "continuous_research",
                    router =
                        FunctionToolRouter(
                            id = "continuous_research",
                            definitionsProvider = continuousResearchToolRouter::toolDefinitions,
                            executor = { toolName, callParams -> continuousResearchToolRouter.handle(toolName, callParams) },
                        ),
                )
        }

        if (shoppingToolRouter != null) {
            capabilities +=
                CapabilityDefinition(
                    id = "shopping",
                    router =
                        FunctionToolRouter(
                            id = "shopping",
                            definitionsProvider = shoppingToolRouter::toolDefinitions,
                            executor = { toolName, callParams -> shoppingToolRouter.handle(toolName, callParams) },
                        ),
                )
        }

        return CapabilityRegistry(capabilities)
    }

    private fun executeCheckpointTool(
        toolName: String,
        params: JsonObject,
    ): Pair<Boolean, String> {
        val handler = checkpointToolHandler ?: return true to "Checkpoint tools are unavailable."
        return when (toolName) {
            McpConstants.CHECKPOINT_LIST_TOOL_NAME -> handler.list(params)
            McpConstants.CHECKPOINT_LATEST_TOOL_NAME -> handler.latest(params)
            McpConstants.CHECKPOINT_GET_TOOL_NAME -> handler.get(params)
            McpConstants.CHECKPOINT_ROLLBACK_TOOL_NAME -> handler.rollback(params)
            McpConstants.CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME -> handler.rollbackLatest(params)
            McpConstants.CHECKPOINT_POLICY_TOOL_NAME -> handler.policy()
            else -> true to "Unsupported checkpoint tool: $toolName"
        }
    }

    private fun hasCheckpointToolFunctions(): Boolean =
        listCheckpoints != null &&
            latestCheckpoint != null &&
            checkpointById != null &&
            rollbackToCheckpoint != null

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
