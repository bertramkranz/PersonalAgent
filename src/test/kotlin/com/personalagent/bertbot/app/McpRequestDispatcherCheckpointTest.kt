package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRequestDispatcherCheckpointTest {
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
}
