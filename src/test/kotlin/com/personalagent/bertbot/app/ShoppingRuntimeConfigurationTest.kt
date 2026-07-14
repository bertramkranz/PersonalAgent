package com.personalagent.bertbot.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingRuntimeConfigurationTest {
    @Test
    fun `shopping configuration defaults to no stores when no env vars are set`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertTrue(configuration.stores.isEmpty())
        assertFalse(configuration.hasEnabledStore)
        assertTrue(configuration.enabledStoresSortedByPriority.isEmpty())
    }

    @Test
    fun `shopping configuration parses a single enabled store`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_1_MODE" to "browse",
                        "BERTBOT_SHOPPING_STORE_1_PRIORITY" to "10",
                        "BERTBOT_SHOPPING_STORE_1_REGION" to "us",
                        "BERTBOT_SHOPPING_STORE_1_CURRENCY" to "usd",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(1, configuration.stores.size)
        assertTrue(configuration.hasEnabledStore)

        val store = configuration.stores.single()
        assertEquals(1, store.index)
        assertTrue(store.enabled)
        assertEquals("browse", store.mode)
        assertEquals(10, store.priority)
        assertEquals("us", store.region)
        assertEquals("usd", store.currency)
    }

    @Test
    fun `shopping configuration parses a disabled store`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "false",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(1, configuration.stores.size)
        assertFalse(configuration.hasEnabledStore)
        assertTrue(configuration.enabledStoresSortedByPriority.isEmpty())
    }

    @Test
    fun `multiple stores are parsed and sorted by priority`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_1_PRIORITY" to "50",
                        "BERTBOT_SHOPPING_STORE_2_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_2_PRIORITY" to "10",
                        "BERTBOT_SHOPPING_STORE_3_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_3_PRIORITY" to "30",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(3, configuration.stores.size)
        assertTrue(configuration.hasEnabledStore)

        val sorted = configuration.enabledStoresSortedByPriority
        assertEquals(3, sorted.size)
        assertEquals(2, sorted[0].index)
        assertEquals(10, sorted[0].priority)
        assertEquals(3, sorted[1].index)
        assertEquals(30, sorted[1].priority)
        assertEquals(1, sorted[2].index)
        assertEquals(50, sorted[2].priority)
    }

    @Test
    fun `disabled stores are excluded from sorted enabled list`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_1_PRIORITY" to "20",
                        "BERTBOT_SHOPPING_STORE_2_ENABLED" to "false",
                        "BERTBOT_SHOPPING_STORE_2_PRIORITY" to "5",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(2, configuration.stores.size)
        assertTrue(configuration.hasEnabledStore)

        val sorted = configuration.enabledStoresSortedByPriority
        assertEquals(1, sorted.size)
        assertEquals(1, sorted.single().index)
    }

    @Test
    fun `shopping configuration applies defaults for missing optional fields`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                    ),
                dotEnvValues = emptyMap(),
            )

        val store = configuration.stores.single()
        assertEquals(DEFAULT_SHOPPING_STORE_MODE, store.mode)
        assertEquals(DEFAULT_SHOPPING_STORE_PRIORITY, store.priority)
        assertNull(store.region)
        assertNull(store.currency)
    }

    @Test
    fun `shopping configuration prefers environment over dotenv`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_1_REGION" to "env-region",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "false",
                        "BERTBOT_SHOPPING_STORE_1_REGION" to "dotenv-region",
                    ),
            )

        val store = configuration.stores.single()
        assertTrue(store.enabled)
        assertEquals("env-region", store.region)
    }

    @Test
    fun `shopping configuration falls back to dotenv values`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_1_MODE" to "checkout",
                        "BERTBOT_SHOPPING_STORE_1_CURRENCY" to "eur",
                    ),
            )

        val store = configuration.stores.single()
        assertTrue(store.enabled)
        assertEquals("checkout", store.mode)
        assertEquals("eur", store.currency)
    }

    @Test
    fun `summarize shopping availability reports disabled when no stores enabled`() {
        val configuration = ShoppingRuntimeConfiguration(stores = emptyList())
        assertEquals("disabled", summarizeShoppingAvailability(configuration))
    }

    @Test
    fun `summarize shopping availability reports enabled stores count`() {
        val configuration =
            ShoppingRuntimeConfiguration(
                stores =
                    listOf(
                        ShoppingStoreRuntimeConfiguration(index = 1, enabled = true),
                        ShoppingStoreRuntimeConfiguration(index = 2, enabled = true),
                    ),
            )
        val summary = summarizeShoppingAvailability(configuration)
        assertTrue(summary.contains("enabled"))
        assertTrue(summary.contains("2"))
    }
}
