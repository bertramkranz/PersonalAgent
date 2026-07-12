package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRequestDispatcherGoogleWorkspaceTest {
    @Test
    fun `tools list includes google workspace proxy tools when router is configured`() {
        val runtimeConfiguration = GoogleWorkspaceRuntimeConfiguration(enabled = true)
        val router = GoogleWorkspaceToolRouter(runtimeConfiguration, FakeGoogleWorkspaceMcpTransport())
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                googleWorkspaceToolRouter = router,
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":35,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val tools = json.getAsJsonObject("result").getAsJsonArray("tools")
        val names = tools.map { it.asJsonObject.get("name").asString }
        assertTrue(names.contains("google_workspace_gmail_createDraft"))
    }

    @Test
    fun `tools call routes google workspace proxy tools when router is configured`() {
        val runtimeConfiguration = GoogleWorkspaceRuntimeConfiguration(enabled = true)
        val router = GoogleWorkspaceToolRouter(runtimeConfiguration, FakeGoogleWorkspaceMcpTransport())
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                googleWorkspaceToolRouter = router,
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":36,"method":"tools/call","params":{"name":"google_workspace_gmail_createDraft","arguments":{"subject":"status"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("google-workspace-ok"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }
}

private class FakeGoogleWorkspaceMcpTransport : GoogleWorkspaceMcpTransport {
    override fun listTools(): List<GoogleWorkspaceDiscoveredTool> {
        val schema = JsonObject().apply { addProperty("type", "object") }
        return listOf(
            GoogleWorkspaceDiscoveredTool(
                name = "gmail_createDraft",
                description = "Create Gmail draft",
                inputSchema = schema,
            ),
        )
    }

    override fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String> {
        return false to "google-workspace-ok tool=$toolName args=$arguments"
    }
}
