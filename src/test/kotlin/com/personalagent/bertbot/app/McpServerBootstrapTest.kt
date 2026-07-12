package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class McpServerBootstrapTest {
    @Test
    fun `dispatcher status includes injected checkpoint rollback policy`() {
        val workspaceRoot = createTempDirectory(prefix = "mcp-bootstrap").toFile()
        workspaceRoot.deleteOnExit()

        val context =
            McpServerBootstrap.createDispatcherContext(
                McpServerBootstrap.DispatcherContextInput(
                    aiRuntimeConfiguration = AiRuntimeConfiguration(provider = "openai", model = "gpt-4o-mini", apiKey = null),
                    macrofactorRuntimeConfiguration = MacrofactorRuntimeConfiguration(enabled = false),
                    googleWorkspaceRuntimeConfiguration = GoogleWorkspaceRuntimeConfiguration(enabled = false),
                    workspaceRoot = workspaceRoot,
                    toolNames = McpConstants.toolNames,
                    checkpointRollbackPolicy =
                        CheckpointRollbackPolicyConfiguration(
                            environment = "production",
                            rollbackEnabled = true,
                            requireConfirm = true,
                            allowInProtectedEnvironment = false,
                        ),
                ),
            )

        val response =
            context.dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":901,"method":"tools/call","params":{"name":"bertbot_status","arguments":{}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString

        assertTrue(text.contains("checkpoint_policy"))
        assertTrue(text.contains("Checkpoint rollback policy:"))
        assertTrue(text.contains("environment=production"))
        assertTrue(text.contains("protectedEnvironment=true"))
        assertTrue(text.contains("rollbackEnabled=true"))
        assertTrue(text.contains("requireConfirm=true"))
        assertTrue(text.contains("allowInProtectedEnvironment=false"))

        context.startup.runtime?.close()
    }
}
