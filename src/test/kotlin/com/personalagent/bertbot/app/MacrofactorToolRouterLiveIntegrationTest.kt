package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacrofactorToolRouterLiveIntegrationTest {
    @Test
    fun `live tools list surfaces upstream macrofactor tools`() {
        assumeLiveTestEnabled()
        val runtimeConfiguration = liveRuntimeConfiguration()
        val router = MacrofactorToolRouter(runtimeConfiguration = runtimeConfiguration)

        val tools = router.toolDefinitions()

        assertTrue(tools.isNotEmpty(), "Expected at least one MacroFactor tool from upstream tools/list.")
        assertTrue(
            tools.all { tool ->
                val name = tool.get("name")?.asString.orEmpty()
                name.startsWith(runtimeConfiguration.toolNamePrefix)
            },
            "Expected all proxy tool names to start with '${runtimeConfiguration.toolNamePrefix}'.",
        )
    }

    @Test
    fun `live tools call succeeds for explicit configured tool`() {
        assumeLiveTestEnabled()
        val runtimeConfiguration = liveRuntimeConfiguration()

        val upstreamToolName = env("BERTBOT_MACROFACTOR_LIVE_TOOL")
        assumeTrue(
            upstreamToolName.isNotBlank(),
            "Set BERTBOT_MACROFACTOR_LIVE_TOOL to an upstream tool name (without prefix) to run live call test.",
        )

        val rawArgs = envOrDefault("BERTBOT_MACROFACTOR_LIVE_ARGS_JSON", "{}")
        val args = parseArgsObject(rawArgs)

        val router = MacrofactorToolRouter(runtimeConfiguration = runtimeConfiguration)
        val params = JsonObject().apply { add("arguments", args) }
        val proxyToolName = "${runtimeConfiguration.toolNamePrefix}$upstreamToolName"

        val outcome = router.handle(toolName = proxyToolName, params = params)

        assertNotNull(outcome, "Expected MacroFactor router to recognize proxy tool '$proxyToolName'.")
        assertTrue(outcome.second.isNotBlank(), "Expected MacroFactor tool call to return content.")
    }

    @Test
    fun `live tools list validates expected tool contract when configured`() {
        assumeLiveTestEnabled()
        val runtimeConfiguration = liveRuntimeConfiguration()

        val expectedUpstreamToolName = env("BERTBOT_MACROFACTOR_EXPECTED_TOOL").ifBlank { env("BERTBOT_MACROFACTOR_LIVE_TOOL") }
        assumeTrue(
            expectedUpstreamToolName.isNotBlank(),
            "Set BERTBOT_MACROFACTOR_EXPECTED_TOOL (or BERTBOT_MACROFACTOR_LIVE_TOOL) to run live contract assertions.",
        )

        val tools = MacrofactorToolRouter(runtimeConfiguration = runtimeConfiguration).toolDefinitions()
        val expectedProxyToolName = "${runtimeConfiguration.toolNamePrefix}$expectedUpstreamToolName"
        val tool = tools.firstOrNull { it.get("name")?.asString == expectedProxyToolName }

        assertNotNull(tool, "Expected proxy tool '$expectedProxyToolName' in tools/list output.")

        val description = tool.get("description")?.asString.orEmpty()
        assertTrue(description.contains("MacroFactor proxy"), "Expected proxy description marker in tool metadata.")

        val inputSchema = tool.get("inputSchema")
        assertTrue(inputSchema != null && inputSchema.isJsonObject, "Expected inputSchema to be a JSON object.")
        val schema = inputSchema.asJsonObject
        assertTrue(
            schema.has("type") || schema.has("properties") || schema.has("required"),
            "Expected inputSchema to include basic contract keys (type/properties/required).",
        )

        val expectedArgName = env("BERTBOT_MACROFACTOR_EXPECTED_ARG")
        if (expectedArgName.isNotBlank()) {
            val properties = schema.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject
            assertTrue(
                properties?.has(expectedArgName) == true,
                "Expected inputSchema.properties to include argument '$expectedArgName'.",
            )
        }
    }

    private fun assumeLiveTestEnabled() {
        assumeTrue(
            env("BERTBOT_MACROFACTOR_LIVE_TEST").equals("true", ignoreCase = true),
            "Set BERTBOT_MACROFACTOR_LIVE_TEST=true to enable live MacroFactor integration tests.",
        )
    }

    private fun liveRuntimeConfiguration(): MacrofactorRuntimeConfiguration {
        val configuration =
            resolveMacrofactorRuntimeConfiguration(
                environment = System.getenv(),
                dotEnvValues = emptyMap(),
            ).copy(enabled = true)

        assumeTrue(
            !configuration.username.isNullOrBlank() && !configuration.password.isNullOrBlank(),
            "Set BERTBOT_MACROFACTOR_USERNAME and BERTBOT_MACROFACTOR_PASSWORD to run live MacroFactor tests.",
        )
        return configuration
    }

    private fun parseArgsObject(rawArgs: String): JsonObject {
        val parsed = JsonParser.parseString(rawArgs)
        require(parsed.isJsonObject) {
            "BERTBOT_MACROFACTOR_LIVE_ARGS_JSON must be a JSON object, received: $rawArgs"
        }
        return parsed.asJsonObject
    }

    private fun env(name: String): String =
        System.getenv(name)?.trim().orEmpty()

    private fun envOrDefault(
        name: String,
        defaultValue: String,
    ): String = env(name).ifBlank { defaultValue }
}
