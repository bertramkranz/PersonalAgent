package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.personalagent.bertbot.config.BertBotAgentConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BertBotRuntimeToolIntegrationsTest {
    @Test
    fun `polymarket integration is skipped when polymarket sub-agent is disabled`() {
        val defaultConfig = BertBotAgentConfig()
        val configWithoutPolymarket =
            defaultConfig.copy(
                subAgents =
                    defaultConfig.subAgents.map { definition ->
                        if (definition.id == "polymarket_analyst") definition.copy(enabled = false) else definition
                    },
            )

        val router = createPolymarketToolRouterOrNull(configWithoutPolymarket)

        assertNull(router)
        assertTrue(buildRuntimeToolIntegrations(null, router).isEmpty())
    }

    @Test
    fun `build runtime tool integrations includes polymarket tools`() {
        val polymarketRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment(emptyMap()))

        val integrations =
            buildRuntimeToolIntegrations(
                googleWorkspaceRouter = null,
                polymarketToolRouter = polymarketRouter,
            )

        assertEquals(1, integrations.size)
        assertEquals("polymarket", integrations.single().id)

        val names =
            integrations
                .single()
                .toolDefinitionsProvider()
                .mapNotNull { definition -> definition.get("name")?.asString }
                .toSet()

        assertTrue(names.contains(McpConstants.POLYMARKET_GAMMA_TOOL_NAME))
        assertTrue(names.contains(McpConstants.POLYMARKET_CLOB_TOOL_NAME))
        assertTrue(names.contains(McpConstants.POLYMARKET_DATA_TOOL_NAME))
    }

    @Test
    fun `polymarket integration executes known tool names`() {
        val polymarketRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment(emptyMap()))
        val integration = buildRuntimeToolIntegrations(null, polymarketRouter).single()

        val params = JsonObject().apply { add("arguments", JsonObject()) }
        val outcome = integration.toolExecutor(McpConstants.POLYMARKET_GAMMA_TOOL_NAME, params)

        assertNotNull(outcome)
        assertTrue(outcome.first)
        assertContains(outcome.second, "Missing required field: operation")
    }

    @Test
    fun `missing required tool-backed integration fails fast`() {
        val error =
            assertFailsWith<IllegalStateException> {
                validateToolBackedSubAgentCoverage(BertBotAgentConfig(), emptyList())
            }

        assertContains(error.message ?: "", "polymarket_analyst->polymarket")
    }

    @Test
    fun `required tool-backed integration passes when wired`() {
        val polymarketRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment(emptyMap()))
        val integrations = buildRuntimeToolIntegrations(null, polymarketRouter)

        validateToolBackedSubAgentCoverage(BertBotAgentConfig(), integrations)
    }

    @Test
    fun `polymarket tool definitions require operation argument`() {
        val polymarketRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment(emptyMap()))
        val definitions = polymarketToolDefinitions(polymarketRouter)

        definitions.forEach { definition ->
            val schema = definition.getAsJsonObject("inputSchema")
            val requiredArgs = schema.getAsJsonArray("required").map { element -> element.asString }
            assertTrue(requiredArgs.contains("operation"))
        }
    }
}
