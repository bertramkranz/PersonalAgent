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
    fun `macrofactor router is not created when macrofactor runtime is disabled`() {
        val router = createMacrofactorToolRouterOrNull(MacrofactorRuntimeConfiguration(enabled = false))

        assertNull(router)
    }

    @Test
    fun `build runtime tool integrations includes macrofactor tools`() {
        val macrofactorRouter = macrofactorToolRouterWithSingleTool()

        val integrations =
            buildRuntimeToolIntegrations(
                googleWorkspaceRouter = null,
                polymarketToolRouter = null,
                macrofactorToolRouter = macrofactorRouter,
            )

        assertEquals(1, integrations.size)
        assertEquals("macrofactor", integrations.single().id)

        val names =
            integrations
                .single()
                .toolDefinitionsProvider()
                .mapNotNull { definition -> definition.get("name")?.asString }
                .toSet()

        assertEquals(setOf("macrofactor_get_targets"), names)
    }

    @Test
    fun `macrofactor integration executes known tool names`() {
        val macrofactorRouter = macrofactorToolRouterWithSingleTool()
        val integration =
            buildRuntimeToolIntegrations(
                googleWorkspaceRouter = null,
                polymarketToolRouter = null,
                macrofactorToolRouter = macrofactorRouter,
            ).single()

        val params = JsonObject().apply { add("arguments", JsonObject()) }
        val outcome = integration.toolExecutor("macrofactor_get_targets", params)

        assertNotNull(outcome)
        assertEquals(false, outcome.first)
        assertContains(outcome.second, "protein=180")
    }

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

    @Test
    fun `polymarket tool definitions constrain operation values per api family`() {
        val polymarketRouter = PolymarketToolRouter(PolymarketApiClient.fromEnvironment(emptyMap()))
        val definitions = polymarketToolDefinitions(polymarketRouter)

        fun operationsFor(toolName: String): Set<String> {
            val definition = definitions.first { it.get("name").asString == toolName }
            val operationSchema = definition.getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("operation")
            return operationSchema.getAsJsonArray("enum").map { element -> element.asString }.toSet()
        }

        val gammaOps = operationsFor(McpConstants.POLYMARKET_GAMMA_TOOL_NAME)
        assertContains(gammaOps, "list_markets")
        assertContains(gammaOps, "search")

        val clobOps = operationsFor(McpConstants.POLYMARKET_CLOB_TOOL_NAME)
        assertContains(clobOps, "get_book")
        assertContains(clobOps, "get_prices_history")

        val dataOps = operationsFor(McpConstants.POLYMARKET_DATA_TOOL_NAME)
        assertContains(dataOps, "get_trades")
        assertContains(dataOps, "get_open_interest")
    }

    private fun macrofactorToolRouterWithSingleTool(): MacrofactorToolRouter {
        val runtimeConfiguration =
            MacrofactorRuntimeConfiguration(
                enabled = true,
                username = "tester",
                password = "tester",
                toolNamePrefix = "macrofactor_",
            )
        val transport =
            object : MacrofactorMcpTransport {
                override fun listTools(): List<MacrofactorDiscoveredTool> =
                    listOf(
                        MacrofactorDiscoveredTool(
                            name = "get_targets",
                            description = "Fetches daily nutrition targets.",
                            inputSchema =
                                JsonObject().apply {
                                    addProperty("type", "object")
                                },
                        ),
                    )

                override fun callTool(
                    toolName: String,
                    arguments: JsonObject,
                ): Pair<Boolean, String> = false to "protein=180 carbs=220 fat=70"
            }

        return MacrofactorToolRouter(runtimeConfiguration = runtimeConfiguration, transport = transport)
    }

    @Test
    fun `validate shopping configuration fails fast when personal shopper is enabled without a store`() {
        val configWithShopperEnabled =
            BertBotAgentConfig(
                subAgents =
                    BertBotAgentConfig().subAgents.map { definition ->
                        if (definition.id == "personal_shopper") definition.copy(enabled = true) else definition
                    },
            )
        val shoppingConfig = ShoppingRuntimeConfiguration(stores = emptyList())

        val error =
            assertFailsWith<IllegalStateException> {
                validateShoppingConfiguration(configWithShopperEnabled, shoppingConfig)
            }

        assertContains(error.message ?: "", "personal_shopper")
        assertContains(error.message ?: "", "BERTBOT_SHOPPING_STORE_1_ENABLED")
    }

    @Test
    fun `validate shopping configuration passes when personal shopper is enabled with a store`() {
        val configWithShopperEnabled =
            BertBotAgentConfig(
                subAgents =
                    BertBotAgentConfig().subAgents.map { definition ->
                        if (definition.id == "personal_shopper") definition.copy(enabled = true) else definition
                    },
            )
        val shoppingConfig =
            ShoppingRuntimeConfiguration(
                stores = listOf(ShoppingStoreRuntimeConfiguration(index = 1, enabled = true)),
            )

        validateShoppingConfiguration(configWithShopperEnabled, shoppingConfig)
    }

    @Test
    fun `validate shopping configuration passes when personal shopper is disabled without a store`() {
        val defaultConfig = BertBotAgentConfig()
        val shoppingConfig = ShoppingRuntimeConfiguration(stores = emptyList())

        validateShoppingConfiguration(defaultConfig, shoppingConfig)
    }
}
