package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaywrightStoreAdapterTest {
    // ── AllowedBrowserActionPolicy ─────────────────────────────────────────────

    @Test
    fun `default policy allows expected browser actions`() {
        val policy = AllowedBrowserActionPolicy()
        assertTrue(policy.isAllowed("navigate"))
        assertTrue(policy.isAllowed("click"))
        assertTrue(policy.isAllowed("fill"))
        assertTrue(policy.isAllowed("read"))
        assertTrue(policy.isAllowed("screenshot"))
        assertTrue(policy.isAllowed("select"))
        assertTrue(policy.isAllowed("hover"))
        assertTrue(policy.isAllowed("scroll"))
    }

    @Test
    fun `default policy blocks unlisted actions`() {
        val policy = AllowedBrowserActionPolicy()
        assertFalse(policy.isAllowed("checkout"))
        assertFalse(policy.isAllowed("submit_order"))
        assertFalse(policy.isAllowed("delete"))
        assertFalse(policy.isAllowed("execute_script"))
    }

    @Test
    fun `policy is case-insensitive`() {
        val policy = AllowedBrowserActionPolicy()
        assertTrue(policy.isAllowed("NAVIGATE"))
        assertTrue(policy.isAllowed("Click"))
        assertTrue(policy.isAllowed("FILL"))
    }

    @Test
    fun `custom allowlist restricts to provided actions only`() {
        val policy = AllowedBrowserActionPolicy(setOf("navigate", "read"))
        assertTrue(policy.isAllowed("navigate"))
        assertTrue(policy.isAllowed("read"))
        assertFalse(policy.isAllowed("click"))
        assertFalse(policy.isAllowed("fill"))
    }

    // ── NoopBrowserStoreAdapter ────────────────────────────────────────────────

    @Test
    fun `noop browser adapter always returns error`() {
        val adapter = NoopBrowserStoreAdapter()
        val (isError, message) = adapter.execute("navigate", JsonObject())
        assertTrue(isError)
        assertContains(message, "not available")
    }

    // ── ApiStoreAdapter ────────────────────────────────────────────────────────

    @Test
    fun `api adapter returns executor result when successful`() {
        val adapter = ApiStoreAdapter { _, _ -> false to "ok-result" }
        val (isError, message) = adapter.execute("read", JsonObject())
        assertFalse(isError)
        assertEquals("ok-result", message)
    }

    @Test
    fun `api adapter returns error message when executor returns null`() {
        val adapter = ApiStoreAdapter { _, _ -> null }
        val (isError, message) = adapter.execute("read", JsonObject())
        assertTrue(isError)
        assertContains(message, "'read'")
    }

    // ── HybridStoreAdapter ─────────────────────────────────────────────────────

    @Test
    fun `hybrid adapter returns api result when api succeeds`() {
        val apiAdapter = ApiStoreAdapter { _, _ -> false to "api-success" }
        val browserAdapter = NoopBrowserStoreAdapter()
        val hybrid = HybridStoreAdapter(apiAdapter, browserAdapter)

        val (isError, message) = hybrid.execute("navigate", JsonObject())
        assertFalse(isError)
        assertEquals("api-success", message)
    }

    @Test
    fun `hybrid adapter falls back to browser when api fails`() {
        val apiAdapter = ApiStoreAdapter { _, _ -> true to "api-error" }
        val browserAdapter =
            object : BrowserStoreAdapter {
                override fun execute(
                    action: String,
                    params: JsonObject,
                ): Pair<Boolean, String> = false to "browser-success"
            }
        val hybrid = HybridStoreAdapter(apiAdapter, browserAdapter)

        val (isError, message) = hybrid.execute("navigate", JsonObject())
        assertFalse(isError, "Expected browser fallback to succeed")
        assertEquals("browser-success", message)
    }

    @Test
    fun `hybrid adapter blocks browser action not on allowlist`() {
        val apiAdapter = ApiStoreAdapter { _, _ -> true to "api-error" }
        val browserAdapter =
            object : BrowserStoreAdapter {
                override fun execute(
                    action: String,
                    params: JsonObject,
                ): Pair<Boolean, String> = false to "should-not-reach"
            }
        val hybrid = HybridStoreAdapter(apiAdapter, browserAdapter)

        val (isError, message) = hybrid.execute("checkout", JsonObject())
        assertTrue(isError)
        assertContains(message, "not permitted")
        assertFalse(message.contains("should-not-reach"))
    }

    @Test
    fun `hybrid adapter returns recommendation when both api and browser fail`() {
        val apiAdapter = ApiStoreAdapter { _, _ -> true to "api-error" }
        val browserAdapter = NoopBrowserStoreAdapter()
        val hybrid = HybridStoreAdapter(apiAdapter, browserAdapter)

        val (isError, message) = hybrid.execute("navigate", JsonObject())
        assertTrue(isError)
        assertContains(message, "manually")
    }

    @Test
    fun `hybrid adapter returns recommendation when browser throws unexpectedly`() {
        val apiAdapter = ApiStoreAdapter { _, _ -> true to "api-error" }
        val browserAdapter =
            object : BrowserStoreAdapter {
                override fun execute(
                    action: String,
                    params: JsonObject,
                ): Pair<Boolean, String> = error("Playwright crashed")
            }
        val hybrid = HybridStoreAdapter(apiAdapter, browserAdapter)

        val (isError, message) = hybrid.execute("navigate", JsonObject())
        assertTrue(isError)
        assertContains(message, "manually")
    }

    // ── resolveStoreAdapter ────────────────────────────────────────────────────

    @Test
    fun `resolveStoreAdapter returns api adapter when playwright disabled`() {
        val config = PlaywrightStoreRuntimeConfiguration(enabled = false)
        val adapter = resolveStoreAdapter("myStore", config, { _, _ -> false to "api-ok" })
        val (isError, message) = adapter.execute("navigate", JsonObject())
        assertFalse(isError)
        assertEquals("api-ok", message)
    }

    @Test
    fun `resolveStoreAdapter returns api adapter when mode is api`() {
        val config = PlaywrightStoreRuntimeConfiguration(enabled = true, defaultMode = StoreAdapterMode.API)
        val adapter = resolveStoreAdapter("myStore", config, { _, _ -> false to "api-ok" })
        val (isError, _) = adapter.execute("navigate", JsonObject())
        assertFalse(isError)
    }

    @Test
    fun `resolveStoreAdapter returns browser adapter when mode is browser`() {
        val config = PlaywrightStoreRuntimeConfiguration(enabled = true, defaultMode = StoreAdapterMode.BROWSER)
        val adapter =
            resolveStoreAdapter(
                "myStore",
                config,
                { _, _ -> false to "api-ok" },
                NoopBrowserStoreAdapter(),
            )
        val (isError, message) = adapter.execute("navigate", JsonObject())
        assertTrue(isError, "Noop browser adapter should return error")
        assertContains(message, "not available")
    }

    @Test
    fun `resolveStoreAdapter with real browser adapter in browser mode returns browser result`() {
        val config = PlaywrightStoreRuntimeConfiguration(enabled = true, defaultMode = StoreAdapterMode.BROWSER)
        val realBrowserAdapter =
            object : BrowserStoreAdapter {
                override fun execute(
                    action: String,
                    params: JsonObject,
                ): Pair<Boolean, String> = false to "browser-page-content"
            }
        val adapter =
            resolveStoreAdapter(
                "myStore",
                config,
                { _, _ -> false to "api-ok" },
                realBrowserAdapter,
            )
        val (isError, message) = adapter.execute("read", JsonObject())
        assertFalse(isError, "Real browser adapter should succeed")
        assertEquals("browser-page-content", message)
    }

    @Test
    fun `resolveStoreAdapter returns hybrid adapter when mode is hybrid`() {
        val config = PlaywrightStoreRuntimeConfiguration(enabled = true, defaultMode = StoreAdapterMode.HYBRID)
        val adapter =
            resolveStoreAdapter(
                "myStore",
                config,
                { _, _ -> true to "api-fails" },
                NoopBrowserStoreAdapter(),
            )
        val (isError, _) = adapter.execute("navigate", JsonObject())
        assertTrue(isError, "Hybrid with noop browser and failing api should still error")
    }

    @Test
    fun `resolveStoreAdapter uses per-store mode override`() {
        val config =
            PlaywrightStoreRuntimeConfiguration(
                enabled = true,
                defaultMode = StoreAdapterMode.API,
                storeModes = mapOf("special" to StoreAdapterMode.HYBRID),
            )
        val adapter =
            resolveStoreAdapter(
                "special",
                config,
                { _, _ -> true to "api-fails" },
                NoopBrowserStoreAdapter(),
            )
        val (isError, message) = adapter.execute("navigate", JsonObject())
        assertTrue(isError)
        assertContains(message, "manually")
    }

    // ── PlaywrightStoreRuntimeConfiguration ────────────────────────────────────

    @Test
    fun `default playwright store configuration is disabled`() {
        val config = PlaywrightStoreRuntimeConfiguration()
        assertFalse(config.enabled)
        assertEquals(StoreAdapterMode.API, config.defaultMode)
    }

    @Test
    fun `resolveMode falls back to defaultMode for unknown store`() {
        val config =
            PlaywrightStoreRuntimeConfiguration(
                enabled = true,
                defaultMode = StoreAdapterMode.HYBRID,
                storeModes = mapOf("known" to StoreAdapterMode.BROWSER),
            )
        assertEquals(StoreAdapterMode.BROWSER, config.resolveMode("known"))
        assertEquals(StoreAdapterMode.HYBRID, config.resolveMode("unknown"))
    }

    // ── resolvePlaywrightStoreRuntimeConfiguration ─────────────────────────────

    @Test
    fun `resolver produces disabled config from empty environment`() {
        val config = resolvePlaywrightStoreRuntimeConfiguration(emptyMap(), emptyMap())
        assertFalse(config.enabled)
        assertEquals(StoreAdapterMode.API, config.defaultMode)
        assertTrue(config.storeModes.isEmpty())
    }

    @Test
    fun `resolver enables playwright from environment`() {
        val env = mapOf("BERTBOT_PLAYWRIGHT_STORE_ENABLED" to "true")
        val config = resolvePlaywrightStoreRuntimeConfiguration(env, emptyMap())
        assertTrue(config.enabled)
    }

    @Test
    fun `resolver resolves default mode from environment`() {
        val env =
            mapOf(
                "BERTBOT_PLAYWRIGHT_STORE_ENABLED" to "true",
                "BERTBOT_PLAYWRIGHT_STORE_DEFAULT_MODE" to "hybrid",
            )
        val config = resolvePlaywrightStoreRuntimeConfiguration(env, emptyMap())
        assertEquals(StoreAdapterMode.HYBRID, config.defaultMode)
    }

    @Test
    fun `resolvePlaywrightStoreRuntimeConfiguration parses per-store modes from environment`() {
        val env =
            mapOf(
                "BERTBOT_PLAYWRIGHT_STORE_ENABLED" to "true",
                "BERTBOT_PLAYWRIGHT_STORE_MODES" to "store1:hybrid,store2:browser",
            )
        val config = resolvePlaywrightStoreRuntimeConfiguration(env, emptyMap())
        assertEquals(StoreAdapterMode.HYBRID, config.resolveMode("store1"))
        assertEquals(StoreAdapterMode.BROWSER, config.resolveMode("store2"))
        assertEquals(StoreAdapterMode.API, config.resolveMode("store3"))
    }

    @Test
    fun `resolvePlaywrightStoreRuntimeConfiguration ignores malformed store mode entries`() {
        val env =
            mapOf(
                "BERTBOT_PLAYWRIGHT_STORE_ENABLED" to "true",
                "BERTBOT_PLAYWRIGHT_STORE_MODES" to "store1:hybrid,bad-entry,store2:browser",
            )
        val config = resolvePlaywrightStoreRuntimeConfiguration(env, emptyMap())
        assertEquals(StoreAdapterMode.HYBRID, config.resolveMode("store1"))
        assertEquals(StoreAdapterMode.BROWSER, config.resolveMode("store2"))
    }

    @Test
    fun `resolvePlaywrightStoreRuntimeConfiguration parses custom allowed browser actions from environment`() {
        val env =
            mapOf(
                "BERTBOT_PLAYWRIGHT_STORE_ENABLED" to "true",
                "BERTBOT_PLAYWRIGHT_STORE_ALLOWED_BROWSER_ACTIONS" to "navigate,read",
            )
        val config = resolvePlaywrightStoreRuntimeConfiguration(env, emptyMap())
        assertEquals(setOf("navigate", "read"), config.allowedBrowserActions)
    }

    @Test
    fun `resolvePlaywrightStoreRuntimeConfiguration falls back to default allowed actions when env is blank`() {
        val env = mapOf("BERTBOT_PLAYWRIGHT_STORE_ENABLED" to "true")
        val config = resolvePlaywrightStoreRuntimeConfiguration(env, emptyMap())
        assertEquals(AllowedBrowserActionPolicy.DEFAULT_ALLOWED_BROWSER_ACTIONS, config.allowedBrowserActions)
    }
}
