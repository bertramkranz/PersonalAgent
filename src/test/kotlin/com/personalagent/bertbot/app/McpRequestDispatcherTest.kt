package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.ingestion.ApprovalRecord
import com.personalagent.bertbot.ingestion.ApprovalUpdateRequest
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRequestDispatcherTest {
    @Test
    fun `initialize returns protocol metadata`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        assertEquals(1, json.get("id").asInt)
        assertEquals("2024-11-05", json.getAsJsonObject("result").get("protocolVersion").asString)
        assertEquals("bertbot", json.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").asString)
    }

    @Test
    fun `tools list exposes ask bertbot tool`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val tools = json.getAsJsonObject("result").getAsJsonArray("tools")
        val names = tools.map { it.asJsonObject.get("name").asString }
        assertTrue(names.contains("ask_bertbot"))
        assertTrue(names.contains("bertbot_status"))
        assertTrue(names.contains("workspace_list_dir"))
        assertTrue(names.contains("workspace_read_file"))
        assertTrue(names.contains("workspace_search"))
    }

    @Test
    fun `tools call routes prompt to bertbot`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { prompt, _ -> "handled: $prompt" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"plan this"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val content = json.getAsJsonObject("result").getAsJsonArray("content")
        assertTrue(content[0].asJsonObject.get("text").asString.contains("handled: plan this"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `ask bertbot attaches workspace evidence for repository verification prompts`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "src/main/kotlin/com/personalagent/bertbot").mkdirs()
        File(workspace, "src/test/kotlin/com/personalagent/bertbot").mkdirs()
        File(workspace, "README.md").writeText("hello")

        var capturedPrompt = ""
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { prompt, _ ->
                    capturedPrompt = prompt
                    "ok"
                },
                workspaceRoot = workspace,
            )

        dispatcher.handle(
            """
            {"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Please review repository architecture with verified file references"}}}
            """.trimIndent(),
        )

        assertTrue(capturedPrompt.contains("Backend-verified workspace evidence"))
        assertTrue(capturedPrompt.contains("top-level entries"))
        assertTrue(capturedPrompt.contains("src/main/kotlin/com/personalagent/bertbot children"))
        assertTrue(capturedPrompt.contains("src/test/kotlin/com/personalagent/bertbot children"))
    }

    @Test
    fun `ask bertbot keeps prompt unchanged for non-repository prompts`() {
        var capturedPrompt = ""
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { prompt, _ ->
                    capturedPrompt = prompt
                    "ok"
                },
            )

        dispatcher.handle(
            """
            {"jsonrpc":"2.0","id":32,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Say hello"}}}
            """.trimIndent(),
        )

        assertEquals("Say hello", capturedPrompt)
    }

    @Test
    fun `ask bertbot rewrites false unavailable claim when repo evidence exists`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "src/main/kotlin/com/personalagent/bertbot").mkdirs()
        File(workspace, "src/test/kotlin/com/personalagent/bertbot").mkdirs()

        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "Backend workspace tooling is unavailable." },
                workspaceRoot = workspace,
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":33,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Please review repository architecture with verified file references"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("Backend workspace tools are available in this session"))
        assertFalse(text.contains("workspace tooling is unavailable", ignoreCase = true))
    }

    @Test
    fun `ask bertbot rewrites uppercase unavailable marker when repo evidence exists`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "src/main/kotlin/com/personalagent/bertbot").mkdirs()

        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "BACKEND_WORKSPACE_UNAVAILABLE" },
                workspaceRoot = workspace,
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":34,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Please review repository architecture with verified file references"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("Backend workspace tools are available in this session"))
    }

    @Test
    fun `ask bertbot status probe returns deterministic backend status`() {
        var calledRuntime = false
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    calledRuntime = true
                    "should not be used"
                },
                statusProvider = { "Connected to bertbot MCP server" },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":35,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"I will check backend health and return status output directly"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("Connected to bertbot MCP server"))
        assertTrue(text.contains("Backend routing: available"))
        assertFalse(calledRuntime)
    }

    @Test
    fun `ask bertbot exact bert_bot status command returns deterministic backend status`() {
        var calledRuntime = false
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    calledRuntime = true
                    "should not be used"
                },
                statusProvider = { "Connected to bertbot MCP server" },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":36,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"bert_bot status"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("Connected to bertbot MCP server"))
        assertTrue(text.contains("Backend routing: available"))
        assertFalse(calledRuntime)
    }

    @Test
    fun `resolve workspace root uses marker directories`() {
        val workspace = createTempDirectory(prefix = "workspace-root").toFile()
        workspace.deleteOnExit()
        File(workspace, "settings.gradle.kts").writeText("rootProject.name = \"PersonalAgent\"")
        val nested = File(workspace, "tmp/deep/path")
        nested.mkdirs()

        val resolved = resolveWorkspaceRoot(environment = emptyMap(), currentDirectory = nested)

        assertEquals(workspace.canonicalFile, resolved)
    }

    @Test
    fun `tools call returns backend status`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                statusProvider = { "Connected to bertbot MCP server" },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"bertbot_status","arguments":{}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val content = json.getAsJsonObject("result").getAsJsonArray("content")
        assertTrue(content[0].asJsonObject.get("text").asString.contains("Connected to bertbot MCP server"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `session loop emits responses for incoming requests`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { prompt, _ -> "handled: $prompt" })
        val inputs =
            mutableListOf(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """.trimIndent(),
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"draft a plan"}}}
                """.trimIndent(),
            )
        val outputs = mutableListOf<String>()

        runMcpSession(
            readLine = { if (inputs.isEmpty()) null else inputs.removeAt(0) },
            writeLine = outputs::add,
            dispatcher = dispatcher,
        )

        assertEquals(2, outputs.size)
        assertTrue(outputs[0].contains("\"protocolVersion\":\"2024-11-05\""))
        assertTrue(outputs[1].contains("handled: draft a plan"))
    }

    @Test
    fun `tools call returns backend unavailable error when runtime is not ready`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> error("Missing AI provider API key (BERTBOT_AI_API_KEY).") },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"status"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val content = json.getAsJsonObject("result").getAsJsonArray("content")
        assertTrue(content[0].asJsonObject.get("text").asString.contains("Missing AI provider API key"))
        assertEquals(true, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `workspace list dir returns directory entries`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "docs").mkdirs()
        File(workspace, "README.md").writeText("hello")

        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" }, workspaceRoot = workspace)

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"workspace_list_dir","arguments":{"path":"."}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("README.md"))
        assertTrue(text.contains("docs/"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `workspace read file returns file contents`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "notes.txt").writeText("line one\nline two")

        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" }, workspaceRoot = workspace)

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"workspace_read_file","arguments":{"path":"notes.txt"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("line one"))
        assertTrue(text.contains("line two"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `workspace search returns matching files`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "alpha.txt").writeText("first line\ncontains token xyz\n")
        File(workspace, "beta.txt").writeText("nothing useful")

        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" }, workspaceRoot = workspace)

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"workspace_search","arguments":{"query":"token xyz"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("alpha.txt:2"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `workspace tools reject paths outside workspace root`() {
        val workspace = createTempDirectory(prefix = "mcp-workspace").toFile()
        workspace.deleteOnExit()

        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" }, workspaceRoot = workspace)

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"workspace_read_file","arguments":{"path":"../secrets.txt"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("outside workspace root"))
        assertEquals(true, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `tools list remains additive when ingestion tools are enabled`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                ingestionControlPlane = FakeIngestionControlPlane(),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val tools = json.getAsJsonObject("result").getAsJsonArray("tools")
        val names = tools.map { it.asJsonObject.get("name").asString }
        assertTrue(names.contains("ask_bertbot"))
        assertTrue(names.contains("bertbot_status"))
        assertTrue(names.contains("ingestion_set_approval"))
        assertTrue(names.contains("ingestion_list_approved_sources"))
        assertTrue(names.contains("ingestion_ingest_manual"))
    }

    @Test
    fun `ingestion approval and manual ingest tools route through control plane`() {
        val control = FakeIngestionControlPlane()
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                ingestionControlPlane = control,
                externalChatResponder = { message, dryRun ->
                    com.personalagent.bertbot.ingestion.ExternalChatOutcome(
                        inbound = message,
                        ingestion = IngestionOutcome(message, IngestionDecision.APPROVED, dryRun = dryRun),
                        outbound =
                            com.personalagent.bertbot.ingestion.NormalizedOutboundMessage(
                                source = message.source,
                                text = "reply from bertbot",
                                replyToMessageId = message.messageId,
                            ),
                        dryRun = dryRun,
                    )
                },
            )

        dispatcher.handle(
            """
            {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"ingestion_set_approval","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-77","approved":true}}}
            """.trimIndent(),
        )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"ingestion_ingest_manual","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-77","text":"hello","dryRun":true}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("decision=approved"))
        assertEquals(1, control.manualIngestCalls)

        val chatResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"ingestion_chat_manual","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-77","text":"hello"}}}
                """.trimIndent(),
            )
        val chatJson = JsonParser.parseString(chatResponse).asJsonObject
        val chatText = chatJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(chatText.contains("reply from bertbot"))
    }

    @Test
    fun `tools list includes macrofactor proxy tools when router is configured`() {
        val macrofactorRouter =
            MacrofactorToolRouter(
                runtimeConfiguration =
                    MacrofactorRuntimeConfiguration(
                        enabled = true,
                        username = "user@example.com",
                        password = "secret",
                    ),
                transport = FakeMacrofactorTransport(),
            )
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                macrofactorToolRouter = macrofactorRouter,
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":14,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val names =
            json
                .getAsJsonObject("result")
                .getAsJsonArray("tools")
                .map { it.asJsonObject.get("name").asString }
        assertTrue(names.contains("macrofactor_get_nutrition"))
    }

    @Test
    fun `tools call routes macrofactor proxy tools through router`() {
        val transport = FakeMacrofactorTransport()
        val macrofactorRouter =
            MacrofactorToolRouter(
                runtimeConfiguration =
                    MacrofactorRuntimeConfiguration(
                        enabled = true,
                        username = "user@example.com",
                        password = "secret",
                    ),
                transport = transport,
            )
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                macrofactorToolRouter = macrofactorRouter,
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"macrofactor_get_nutrition","arguments":{"day":"2026-07-11"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("calories=2400"))
        assertEquals("get_nutrition", transport.lastToolName)
    }
}

private class FakeIngestionControlPlane : IngestionControlPlane {
    private val approvals = mutableListOf<ApprovalRecord>()
    var manualIngestCalls: Int = 0

    override fun setApproval(request: ApprovalUpdateRequest): ApprovalRecord {
        val record = ApprovalRecord(source = request.source, scope = request.scope, approved = request.approved)
        approvals.removeAll { it.source == request.source }
        approvals.add(record)
        return record
    }

    override fun listApprovedSources(): List<ApprovalRecord> = approvals.filter { it.approved }

    override fun ingestManual(
        messages: List<NormalizedIngestionMessage>,
        dryRun: Boolean,
    ): List<IngestionOutcome> {
        manualIngestCalls += 1
        return messages.map { message ->
            IngestionOutcome(
                message = message,
                decision = IngestionDecision.APPROVED,
                dryRun = dryRun,
            )
        }
    }
}

private class FakeMacrofactorTransport : MacrofactorMcpTransport {
    var lastToolName: String? = null

    override fun listTools(): List<MacrofactorDiscoveredTool> {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        schema.add("properties", JsonObject())
        return listOf(
            MacrofactorDiscoveredTool(
                name = "get_nutrition",
                description = "Get nutrition entries for a day.",
                inputSchema = schema,
            ),
        )
    }

    override fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String> {
        lastToolName = toolName
        return false to "calories=2400"
    }
}
