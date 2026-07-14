package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingToolRouterTest {
    private val defaultConfig =
        ShoppingRuntimeConfiguration(
            enabled = true,
            budgetLimitCents = 5_000L,
            minSellerTrustScore = 0.7,
        )

    private fun router(config: ShoppingRuntimeConfiguration = defaultConfig) = ShoppingToolRouter(config)

    // region — tool definitions

    @Test
    fun `tool definitions return one tool with correct name`() {
        val definitions = router().toolDefinitions()
        assertEquals(1, definitions.size)
        assertEquals(SHOPPING_TOOL_NAME, definitions.single().get("name").asString)
    }

    @Test
    fun `tool definition requires operation field`() {
        val definition = router().toolDefinitions().single()
        val required =
            definition
                .getAsJsonObject("inputSchema")
                .getAsJsonArray("required")
                .map { it.asString }
        assertContains(required, "operation")
    }

    @Test
    fun `tool definition operation enum contains allowed operations only`() {
        val definition = router().toolDefinitions().single()
        val operationSchema =
            definition
                .getAsJsonObject("inputSchema")
                .getAsJsonObject("properties")
                .getAsJsonObject("operation")
        val ops = operationSchema.getAsJsonArray("enum").map { it.asString }.toSet()
        assertEquals(setOf("cart_prepare", "checkout_prepare", "compare", "details", "search"), ops)
    }

    // region — routing

    @Test
    fun `returns null for unknown tool names`() {
        val result = router().handle("other_tool", params("search"))
        assertNull(result)
    }

    @Test
    fun `returns null for null tool name`() {
        val result = router().handle(null, params("search"))
        assertNull(result)
    }

    // region — read-only operations (no confirmation required)

    @Test
    fun `search succeeds with query`() {
        val params =
            params("search") {
                addProperty("query", "running shoes")
            }
        val result = router().handle(SHOPPING_TOOL_NAME, params)
        assertNotNull(result)
        assertEquals(false, result.first)
        assertContains(result.second, "search")
        assertContains(result.second, "running shoes")
    }

    @Test
    fun `search returns budget and seller trust limits`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("search") { addProperty("query", "headphones") })
        assertNotNull(result)
        assertContains(result.second, "5000")
        assertContains(result.second, "0.7")
    }

    @Test
    fun `search fails without query`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("search"))
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second, "query")
    }

    @Test
    fun `details succeeds with item_id`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("details") { addProperty("item_id", "item-123") },
            )
        assertNotNull(result)
        assertEquals(false, result.first)
        assertContains(result.second, "item-123")
    }

    @Test
    fun `details fails without item_id`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("details"))
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second, "item_id")
    }

    @Test
    fun `compare succeeds with item_id`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("compare") { addProperty("item_id", "item-456") },
            )
        assertNotNull(result)
        assertEquals(false, result.first)
        assertContains(result.second, "item-456")
    }

    @Test
    fun `compare returns budget and seller trust limits`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("compare") { addProperty("item_id", "item-456") },
            )
        assertNotNull(result)
        assertContains(result.second, "5000")
        assertContains(result.second, "0.7")
    }

    @Test
    fun `compare fails without item_id`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("compare"))
        assertNotNull(result)
        assertEquals(true, result.first)
    }

    // region — confirmation-required operations

    @Test
    fun `cart_prepare succeeds with confirmation token and valid offer`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                    addProperty("confirmation_token", "user-confirmed-abc")
                    addProperty("price_cents", 1_000)
                    addProperty("seller_trust_score", 0.9)
                },
            )
        assertNotNull(result)
        assertEquals(false, result.first)
        assertContains(result.second, "cart_prepare")
        assertContains(result.second, "no_order_placed")
    }

    @Test
    fun `cart_prepare is denied without confirmation token`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "confirmation")
    }

    @Test
    fun `cart_prepare is denied with empty confirmation token`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                    addProperty("confirmation_token", "   ")
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
    }

    @Test
    fun `checkout_prepare succeeds with confirmation token and valid offer`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("checkout_prepare") {
                    addProperty("item_id", "item-abc")
                    addProperty("confirmation_token", "tok-xyz")
                    addProperty("price_cents", 2_000)
                    addProperty("seller_trust_score", 0.85)
                },
            )
        assertNotNull(result)
        assertEquals(false, result.first)
        assertContains(result.second, "checkout_prepare")
        assertContains(result.second, "no_order_placed")
    }

    @Test
    fun `checkout_prepare is denied without confirmation token`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("checkout_prepare") { addProperty("item_id", "item-abc") },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "confirmation")
    }

    // region — budget guardrail

    @Test
    fun `cart_prepare is rejected when price exceeds budget`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                    addProperty("confirmation_token", "tok")
                    addProperty("price_cents", 6_000)
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "budget")
    }

    @Test
    fun `checkout_prepare is rejected when price exceeds budget`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("checkout_prepare") {
                    addProperty("item_id", "item-abc")
                    addProperty("confirmation_token", "tok")
                    addProperty("price_cents", 99_999)
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "budget")
    }

    @Test
    fun `cart_prepare succeeds when price exactly equals budget limit`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                    addProperty("confirmation_token", "tok")
                    addProperty("price_cents", 5_000)
                },
            )
        assertNotNull(result)
        assertEquals(false, result.first)
    }

    // region — seller trust guardrail

    @Test
    fun `cart_prepare is rejected when seller trust score is below threshold`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                    addProperty("confirmation_token", "tok")
                    addProperty("seller_trust_score", 0.5)
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "seller trust")
    }

    @Test
    fun `checkout_prepare is rejected when seller trust score is below threshold`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("checkout_prepare") {
                    addProperty("item_id", "item-abc")
                    addProperty("confirmation_token", "tok")
                    addProperty("seller_trust_score", 0.1)
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "seller trust")
    }

    @Test
    fun `cart_prepare succeeds when seller trust score equals minimum threshold`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("cart_prepare") {
                    addProperty("item_id", "item-789")
                    addProperty("confirmation_token", "tok")
                    addProperty("seller_trust_score", 0.7)
                },
            )
        assertNotNull(result)
        assertEquals(false, result.first)
    }

    // region — place_order hard block

    @Test
    fun `place_order is hard-blocked`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("place_order"))
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "blocked")
    }

    @Test
    fun `submit_order is hard-blocked`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("submit_order"))
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "blocked")
    }

    @Test
    fun `confirm_order is hard-blocked`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("confirm_order"))
        assertNotNull(result)
        assertEquals(true, result.first)
    }

    @Test
    fun `buy_now is hard-blocked`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("buy_now"))
        assertNotNull(result)
        assertEquals(true, result.first)
    }

    @Test
    fun `purchase is hard-blocked`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("purchase"))
        assertNotNull(result)
        assertEquals(true, result.first)
    }

    @Test
    fun `place_order is blocked even with confirmation token and within budget`() {
        val result =
            router().handle(
                SHOPPING_TOOL_NAME,
                params("place_order") {
                    addProperty("item_id", "item-xyz")
                    addProperty("confirmation_token", "user-confirmed")
                    addProperty("price_cents", 100)
                    addProperty("seller_trust_score", 0.99)
                },
            )
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "blocked")
    }

    // region — unknown operation

    @Test
    fun `unknown operation is rejected with allowed list`() {
        val result = router().handle(SHOPPING_TOOL_NAME, params("fly_to_moon"))
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "unknown operation")
    }

    // region — missing operation

    @Test
    fun `missing operation field is rejected`() {
        val arguments = JsonObject()
        val params = JsonObject().apply { add("arguments", arguments) }
        val result = router().handle(SHOPPING_TOOL_NAME, params)
        assertNotNull(result)
        assertEquals(true, result.first)
        assertContains(result.second.lowercase(), "operation")
    }

    // region — config env var resolution

    @Test
    fun `resolveShoppingRuntimeConfiguration reads enabled flag from environment`() {
        val config = resolveShoppingRuntimeConfiguration(mapOf("BERTBOT_SHOPPING_ENABLED" to "true"), emptyMap())
        assertTrue(config.enabled)
    }

    @Test
    fun `resolveShoppingRuntimeConfiguration reads budget limit from environment`() {
        val config =
            resolveShoppingRuntimeConfiguration(
                mapOf("BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS" to "20000"),
                emptyMap(),
            )
        assertEquals(20_000L, config.budgetLimitCents)
    }

    @Test
    fun `resolveShoppingRuntimeConfiguration reads min seller trust score from environment`() {
        val config =
            resolveShoppingRuntimeConfiguration(
                mapOf("BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE" to "0.9"),
                emptyMap(),
            )
        assertEquals(0.9, config.minSellerTrustScore)
    }

    @Test
    fun `resolveShoppingRuntimeConfiguration uses defaults when no env vars are set`() {
        val config = resolveShoppingRuntimeConfiguration(emptyMap(), emptyMap())
        assertEquals(DEFAULT_SHOPPING_ENABLED, config.enabled)
        assertEquals(DEFAULT_SHOPPING_BUDGET_LIMIT_CENTS, config.budgetLimitCents)
        assertEquals(DEFAULT_SHOPPING_MIN_SELLER_TRUST_SCORE, config.minSellerTrustScore)
    }

    @Test
    fun `resolveShoppingRuntimeConfiguration coerces min seller trust score to 0 to 1 range`() {
        val config =
            resolveShoppingRuntimeConfiguration(
                mapOf("BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE" to "1.5"),
                emptyMap(),
            )
        assertEquals(1.0, config.minSellerTrustScore)
    }

    @Test
    fun `resolveShoppingRuntimeConfiguration coerces budget limit to at least 0`() {
        val config =
            resolveShoppingRuntimeConfiguration(
                mapOf("BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS" to "-100"),
                emptyMap(),
            )
        assertEquals(0L, config.budgetLimitCents)
    }

    // region — helpers

    private fun params(
        operation: String,
        configure: JsonObject.() -> Unit = {},
    ): JsonObject {
        val arguments =
            JsonObject().apply {
                addProperty("operation", operation)
                configure()
            }
        return JsonObject().apply { add("arguments", arguments) }
    }
}
