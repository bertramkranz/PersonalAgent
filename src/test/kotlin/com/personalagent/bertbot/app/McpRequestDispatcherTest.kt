package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.ingestion.ApprovalRecord
import com.personalagent.bertbot.ingestion.ApprovalUpdateRequest
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
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
        assertTrue(names.contains("polymarket_gamma_query"))
        assertTrue(names.contains("polymarket_clob_query"))
        assertTrue(names.contains("polymarket_data_query"))
        assertTrue(names.contains("checkpoint_list"))
        assertTrue(names.contains("checkpoint_latest"))
        assertTrue(names.contains("checkpoint_get"))
        assertTrue(names.contains("checkpoint_rollback"))
        assertTrue(names.contains("checkpoint_rollback_latest"))
        assertTrue(names.contains("checkpoint_policy"))
    }

    @Test
    fun `checkpoint tools return list latest and by id for scope`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = "trace-1",
                nodeId = "node-1",
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val c2 =
            BertBotCheckpoint(
                checkpointId = "cp-2",
                scopeKey = "scope-a",
                traceId = "trace-2",
                nodeId = "node-2",
                createdAtEpochMillis = 2000L,
                state = BertBotState(lastUserMessage = "second"),
            )
        val byScope = mapOf("scope-a" to listOf(c1, c2))
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { _, _ -> BertBotState(lastUserMessage = "rolled") },
            )

        val listResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":801,"method":"tools/call","params":{"name":"checkpoint_list","arguments":{"scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val listJson = JsonParser.parseString(listResponse).asJsonObject
        val listText = listJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(listText.contains("checkpointId=cp-1"))
        assertTrue(listText.contains("checkpointId=cp-2"))
        assertFalse(listJson.getAsJsonObject("result").get("isError").asBoolean)

        val latestResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":802,"method":"tools/call","params":{"name":"checkpoint_latest","arguments":{"scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val latestJson = JsonParser.parseString(latestResponse).asJsonObject
        val latestText = latestJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(latestText.contains("checkpointId=cp-2"))
        assertFalse(latestJson.getAsJsonObject("result").get("isError").asBoolean)

        val getResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":803,"method":"tools/call","params":{"name":"checkpoint_get","arguments":{"checkpointId":"cp-1","scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val getJson = JsonParser.parseString(getResponse).asJsonObject
        val getText = getJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(getText.contains("checkpointId=cp-1"))
        assertFalse(getJson.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `checkpoint rollback requires confirm flag and updates state when confirmed`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val byScope = mapOf("scope-a" to listOf(c1))
        var rolledCheckpoint: String? = null
        var rolledScope: String? = null
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { checkpointId, scopeKey ->
                    rolledCheckpoint = checkpointId
                    rolledScope = scopeKey
                    BertBotState(lastUserMessage = "restored")
                },
            )

        val rejectedResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":804,"method":"tools/call","params":{"name":"checkpoint_rollback","arguments":{"checkpointId":"cp-1","scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val rejectedJson = JsonParser.parseString(rejectedResponse).asJsonObject
        val rejectedText = rejectedJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(rejectedText.contains("confirm=true"))
        assertTrue(rejectedJson.getAsJsonObject("result").get("isError").asBoolean)
        assertNull(rolledCheckpoint)
        assertNull(rolledScope)

        val acceptedResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":805,"method":"tools/call","params":{"name":"checkpoint_rollback","arguments":{"checkpointId":"cp-1","scopeKey":"scope-a","confirm":true}}}
                """.trimIndent(),
            )
        val acceptedJson = JsonParser.parseString(acceptedResponse).asJsonObject
        val acceptedText = acceptedJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(acceptedText.contains("Rollback completed."))
        assertFalse(acceptedJson.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals("cp-1", rolledCheckpoint)
        assertEquals("scope-a", rolledScope)
    }

    @Test
    fun `checkpoint rollback latest requires confirm and restores most recent checkpoint`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val c2 =
            BertBotCheckpoint(
                checkpointId = "cp-2",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 2000L,
                state = BertBotState(lastUserMessage = "second"),
            )
        val byScope = mapOf("scope-a" to listOf(c1, c2))
        var rolledCheckpoint: String? = null
        var rolledScope: String? = null
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { checkpointId, scopeKey ->
                    rolledCheckpoint = checkpointId
                    rolledScope = scopeKey
                    BertBotState(lastUserMessage = "restored")
                },
            )

        val rejectedResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8053,"method":"tools/call","params":{"name":"checkpoint_rollback_latest","arguments":{"scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val rejectedJson = JsonParser.parseString(rejectedResponse).asJsonObject
        val rejectedText = rejectedJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(rejectedText.contains("confirm=true"))
        assertTrue(rejectedJson.getAsJsonObject("result").get("isError").asBoolean)

        val acceptedResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8054,"method":"tools/call","params":{"name":"checkpoint_rollback_latest","arguments":{"scopeKey":"scope-a","confirm":true}}}
                """.trimIndent(),
            )
        val acceptedJson = JsonParser.parseString(acceptedResponse).asJsonObject
        val acceptedText = acceptedJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(acceptedText.contains("Rollback completed to latest checkpoint cp-2"))
        assertFalse(acceptedJson.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals("cp-2", rolledCheckpoint)
        assertEquals("scope-a", rolledScope)
    }

    @Test
    fun `checkpoint rollback latest is blocked in protected environment by default`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val byScope = mapOf("scope-a" to listOf(c1))
        var rollbackCalled = false
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { _, _ ->
                    rollbackCalled = true
                    BertBotState(lastUserMessage = "restored")
                },
                checkpointRollbackPolicy = CheckpointRollbackPolicyConfiguration(environment = "production"),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8055,"method":"tools/call","params":{"name":"checkpoint_rollback_latest","arguments":{"scopeKey":"scope-a","confirm":true}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("blocked in protected environment"))
        assertTrue(json.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals(false, rollbackCalled)
    }

    @Test
    fun `checkpoint rollback latest fails when scope has no checkpoints`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { _ -> emptyList() },
                latestCheckpoint = { _ -> null },
                checkpointById = { _, _ -> null },
                rollbackToCheckpoint = { _, _ -> BertBotState(lastUserMessage = "restored") },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8056,"method":"tools/call","params":{"name":"checkpoint_rollback_latest","arguments":{"scopeKey":"missing","confirm":true}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("No checkpoints found."))
        assertTrue(json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `checkpoint rollback is blocked in protected environment by default`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val byScope = mapOf("scope-a" to listOf(c1))
        var rollbackCalled = false
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { _, _ ->
                    rollbackCalled = true
                    BertBotState(lastUserMessage = "restored")
                },
                checkpointRollbackPolicy = CheckpointRollbackPolicyConfiguration(environment = "production"),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8051,"method":"tools/call","params":{"name":"checkpoint_rollback","arguments":{"checkpointId":"cp-1","scopeKey":"scope-a","confirm":true}}}
                """.trimIndent(),
            )
        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("blocked in protected environment"))
        assertTrue(json.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals(false, rollbackCalled)
    }

    @Test
    fun `checkpoint rollback can be enabled in protected environment with explicit override`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val byScope = mapOf("scope-a" to listOf(c1))
        var rollbackCalled = false
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { _, _ ->
                    rollbackCalled = true
                    BertBotState(lastUserMessage = "restored")
                },
                checkpointRollbackPolicy =
                    CheckpointRollbackPolicyConfiguration(
                        environment = "production",
                        allowInProtectedEnvironment = true,
                    ),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8052,"method":"tools/call","params":{"name":"checkpoint_rollback","arguments":{"checkpointId":"cp-1","scopeKey":"scope-a","confirm":true}}}
                """.trimIndent(),
            )
        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("Rollback completed."))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals(true, rollbackCalled)
    }

    @Test
    fun `checkpoint tools return unavailable when runtime functions are not wired`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { _, _ -> "unused" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":806,"method":"tools/call","params":{"name":"checkpoint_list","arguments":{}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("Checkpoint tools are unavailable"))
        assertTrue(json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `checkpoint policy tool reports active rollback guardrails`() {
        val c1 =
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = null,
                nodeId = null,
                createdAtEpochMillis = 1000L,
                state = BertBotState(lastUserMessage = "first"),
            )
        val byScope = mapOf("scope-a" to listOf(c1))
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                listCheckpoints = { scopeKey -> byScope[scopeKey] ?: emptyList() },
                latestCheckpoint = { scopeKey -> byScope[scopeKey]?.maxByOrNull { it.createdAtEpochMillis } },
                checkpointById = { checkpointId, scopeKey -> byScope[scopeKey]?.firstOrNull { it.checkpointId == checkpointId } },
                rollbackToCheckpoint = { _, _ -> BertBotState(lastUserMessage = "restored") },
                checkpointRollbackPolicy =
                    CheckpointRollbackPolicyConfiguration(
                        environment = "production",
                        rollbackEnabled = true,
                        requireConfirm = true,
                        allowInProtectedEnvironment = false,
                    ),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":8061,"method":"tools/call","params":{"name":"checkpoint_policy","arguments":{}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("environment=production"))
        assertTrue(text.contains("protectedEnvironment=true"))
        assertTrue(text.contains("rollbackEnabled=true"))
        assertTrue(text.contains("requireConfirm=true"))
        assertTrue(text.contains("allowInProtectedEnvironment=false"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `polymarket gamma query routes to configured gamma endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/markets") { exchange ->
            val body = """[{"slug":"btc-above-100k","active":true}]"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client =
                PolymarketApiClient(
                    gammaBaseUrl = "http://127.0.0.1:${server.address.port}",
                    clobBaseUrl = "http://127.0.0.1:${server.address.port}",
                    dataBaseUrl = "http://127.0.0.1:${server.address.port}",
                )
            val dispatcher =
                McpRequestDispatcher(
                    respondToPrompt = { _, _ -> "unused" },
                    polymarketToolRouter = PolymarketToolRouter(client),
                )

            val response =
                dispatcher.handle(
                    """
                    {"jsonrpc":"2.0","id":120,"method":"tools/call","params":{"name":"polymarket_gamma_query","arguments":{"operation":"list_markets","limit":1}}}
                    """.trimIndent(),
                )

            val json = JsonParser.parseString(response).asJsonObject
            val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
            assertTrue(text.contains("source=gamma operation=list_markets http_status=200"))
            assertTrue(text.contains("btc-above-100k"))
            assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `polymarket data query reports upstream http errors`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/positions") { exchange ->
            val body = "{\"error\":\"missing user\"}"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client =
                PolymarketApiClient(
                    gammaBaseUrl = "http://127.0.0.1:${server.address.port}",
                    clobBaseUrl = "http://127.0.0.1:${server.address.port}",
                    dataBaseUrl = "http://127.0.0.1:${server.address.port}",
                )
            val dispatcher =
                McpRequestDispatcher(
                    respondToPrompt = { _, _ -> "unused" },
                    polymarketToolRouter = PolymarketToolRouter(client),
                )

            val response =
                dispatcher.handle(
                    """
                    {"jsonrpc":"2.0","id":121,"method":"tools/call","params":{"name":"polymarket_data_query","arguments":{"operation":"get_positions"}}}
                    """.trimIndent(),
                )

            val json = JsonParser.parseString(response).asJsonObject
            val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
            assertTrue(text.contains("source=data operation=get_positions http_status=400"))
            assertTrue(text.contains("missing user"))
            assertEquals(true, json.getAsJsonObject("result").get("isError").asBoolean)
        } finally {
            server.stop(0)
        }
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
    fun `tools list includes continuous research tools when router is configured`() {
        val workspace = createTempDirectory(prefix = "mcp-research-workspace").toFile()
        workspace.deleteOnExit()
        val researchService =
            ContinuousImprovementResearchService(
                config = com.personalagent.bertbot.config.BertBotAgentConfig(),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(File(workspace, "recommendations.json")),
            )
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                continuousResearchToolRouter = ContinuousResearchToolRouter(researchService),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val tools = json.getAsJsonObject("result").getAsJsonArray("tools")
        val names = tools.map { it.asJsonObject.get("name").asString }
        assertTrue(names.contains(RESEARCH_LIST_TOOL_NAME))
        assertTrue(names.contains(RESEARCH_RUN_NOW_TOOL_NAME))
    }

    @Test
    fun `tools call can trigger continuous research cycle`() {
        val workspace = createTempDirectory(prefix = "mcp-research-run").toFile()
        workspace.deleteOnExit()
        val researchService =
            ContinuousImprovementResearchService(
                config = com.personalagent.bertbot.config.BertBotAgentConfig(),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(File(workspace, "recommendations.json")),
            )
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                continuousResearchToolRouter = ContinuousResearchToolRouter(researchService),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":303,"method":"tools/call","params":{"name":"repo_improvement_run_now","arguments":{"reason":"test"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("executed=true"))
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
    fun `ask bertbot status probes return deterministic backend status`() {
        var calledRuntime = false
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    calledRuntime = true
                    "should not be used"
                },
                statusProvider = { "Connected to bertbot MCP server" },
            )

        val prompts =
            listOf(
                "I will check backend health and return status output directly",
                "bert_bot status",
            )

        prompts.forEachIndexed { index, prompt ->
            val response =
                dispatcher.handle(
                    """
                    {"jsonrpc":"2.0","id":${35 + index},"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"$prompt"}}}
                    """.trimIndent(),
                )

            val json = JsonParser.parseString(response).asJsonObject
            val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
            assertTrue(text.contains("Connected to bertbot MCP server"))
            assertTrue(text.contains("Backend routing: available"))
        }
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
        assertTrue(names.contains("checkpoint_list"))
        assertTrue(names.contains("checkpoint_latest"))
        assertTrue(names.contains("checkpoint_get"))
        assertTrue(names.contains("checkpoint_rollback"))
        assertTrue(names.contains("checkpoint_rollback_latest"))
        assertTrue(names.contains("checkpoint_policy"))
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
