package com.personalagent.bertbot.app

import com.google.gson.JsonObject

/** Selects how a store adapter resolves tool-call executions. */
enum class StoreAdapterMode {
    /** Use API-based execution only. */
    API,

    /** Use browser-based execution only via Playwright. */
    BROWSER,

    /** Try API first; fall back to browser on failure. */
    HYBRID,
}

/**
 * Policy that enforces an explicit allowlist of browser actions.
 * Actions not present in the allowlist are blocked and return a user-facing error.
 */
class AllowedBrowserActionPolicy(
    val allowedActions: Set<String> = DEFAULT_ALLOWED_BROWSER_ACTIONS,
) {
    fun isAllowed(action: String): Boolean = action.lowercase() in allowedActions

    companion object {
        val DEFAULT_ALLOWED_BROWSER_ACTIONS: Set<String> =
            setOf(
                "navigate",
                "click",
                "fill",
                "read",
                "screenshot",
                "select",
                "hover",
                "scroll",
            )
    }
}

/** Core contract for all store adapters, regardless of backing transport. */
internal interface StoreAdapter {
    fun execute(
        action: String,
        params: JsonObject,
    ): Pair<Boolean, String>
}

/** Marker interface for browser-backed adapters (Playwright). */
internal interface BrowserStoreAdapter : StoreAdapter

/**
 * Noop browser adapter used when Playwright is not configured or unavailable.
 * Always returns an error, triggering the graceful-fallback path in the caller.
 */
internal class NoopBrowserStoreAdapter : BrowserStoreAdapter {
    override fun execute(
        action: String,
        params: JsonObject,
    ): Pair<Boolean, String> = true to "Browser automation is not available in this runtime."
}

/** API-backed store adapter that delegates to an arbitrary executor function. */
internal class ApiStoreAdapter(
    private val executor: (action: String, params: JsonObject) -> Pair<Boolean, String>?,
) : StoreAdapter {
    override fun execute(
        action: String,
        params: JsonObject,
    ): Pair<Boolean, String> = executor(action, params) ?: (true to "API store returned no result for action '$action'.")
}

/**
 * Hybrid store adapter: tries the API adapter first.
 * If the API call fails, it attempts the browser adapter—provided the action is permitted
 * by [policy]. When both paths fail or the action is blocked, it returns a safe
 * recommendation-only response rather than an unrecoverable error.
 */
internal class HybridStoreAdapter(
    private val apiAdapter: StoreAdapter,
    private val browserAdapter: BrowserStoreAdapter,
    private val policy: AllowedBrowserActionPolicy = AllowedBrowserActionPolicy(),
) : StoreAdapter {
    override fun execute(
        action: String,
        params: JsonObject,
    ): Pair<Boolean, String> {
        val apiResult = runCatching { apiAdapter.execute(action, params) }.getOrNull()
        if (apiResult != null && !apiResult.first) {
            return apiResult
        }

        if (!policy.isAllowed(action)) {
            return true to blockedActionMessage(action)
        }

        val browserResult = runCatching { browserAdapter.execute(action, params) }.getOrNull()
        if (browserResult != null && !browserResult.first) {
            return browserResult
        }

        return true to recommendationOnlyMessage(action)
    }

    private fun blockedActionMessage(action: String): String =
        "Browser action '$action' is not permitted by the store safety policy. " +
            "Please perform it manually or contact support."

    private fun recommendationOnlyMessage(action: String): String =
        "The '$action' operation could not be completed automatically. " +
            "Please perform it manually or retry when the service is available."
}

/**
 * Resolves a [StoreAdapter] for the given [storeName] based on the [PlaywrightStoreRuntimeConfiguration].
 *
 * - Returns an [ApiStoreAdapter] when [PlaywrightStoreRuntimeConfiguration.enabled] is false
 *   or the resolved mode is [StoreAdapterMode.API].
 * - Returns a [BrowserStoreAdapter] when mode is [StoreAdapterMode.BROWSER].
 * - Returns a [HybridStoreAdapter] when mode is [StoreAdapterMode.HYBRID].
 */
internal fun resolveStoreAdapter(
    storeName: String,
    configuration: PlaywrightStoreRuntimeConfiguration,
    apiExecutor: (action: String, params: JsonObject) -> Pair<Boolean, String>?,
    browserAdapter: BrowserStoreAdapter = NoopBrowserStoreAdapter(),
): StoreAdapter {
    val apiAdapter = ApiStoreAdapter(apiExecutor)
    val policy = AllowedBrowserActionPolicy(configuration.allowedBrowserActions)

    if (!configuration.enabled) {
        return apiAdapter
    }

    return when (configuration.resolveMode(storeName)) {
        StoreAdapterMode.API -> apiAdapter
        StoreAdapterMode.BROWSER -> browserAdapter
        StoreAdapterMode.HYBRID -> HybridStoreAdapter(apiAdapter, browserAdapter, policy)
    }
}
