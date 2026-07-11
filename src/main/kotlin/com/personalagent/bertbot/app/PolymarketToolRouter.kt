package com.personalagent.bertbot.app

import com.google.gson.JsonObject

internal class PolymarketToolRouter(
    private val apiClient: PolymarketApiClient,
) {
    fun handle(
        toolName: String,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        val arguments = params.objectValue("arguments") ?: params
        val operation =
            arguments.stringValue("operation")
                ?: return true to "Missing required field: operation"

        val (apiFamily, response) =
            try {
                when (toolName) {
                    "polymarket_gamma_query" -> "gamma" to executeGamma(operation, arguments)
                    "polymarket_clob_query" -> "clob" to executeClob(operation, arguments)
                    "polymarket_data_query" -> "data" to executeData(operation, arguments)
                    else -> return null
                }
            } catch (e: IllegalArgumentException) {
                return true to (e.message ?: "Invalid Polymarket request")
            } catch (e: Exception) {
                return true to "Polymarket request failed: ${e.message ?: "unknown error"}"
            }

        return PolymarketApiClient.formatResponseForTool(apiFamily, operation, response)
    }

    private fun executeGamma(
        operation: String,
        arguments: JsonObject,
    ): PolymarketHttpResponse =
        when (operation) {
            "list_markets" -> executeGammaListMarkets(arguments)
            "list_events" -> executeGammaListEvents(arguments)
            "get_market_by_slug" -> executeGammaGetMarketBySlug(arguments)
            "get_event_by_slug" -> executeGammaGetEventBySlug(arguments)
            "search" -> executeGammaSearch(arguments)
            "list_markets_keyset" -> executeGammaListMarketsKeyset(arguments)
            "list_events_keyset" -> executeGammaListEventsKeyset(arguments)

            else -> throw IllegalArgumentException("Unsupported gamma operation: $operation")
        }

    private fun executeGammaListMarkets(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/markets",
            queryParameters =
                collectQueryParameters(
                    arguments,
                    "limit",
                    "offset",
                    "order",
                    "ascending",
                    "id",
                    "slug",
                    "active",
                    "closed",
                    "archived",
                    "tag_id",
                    "related_tags",
                    "liquidity_num_min",
                    "liquidity_num_max",
                    "volume_num_min",
                    "volume_num_max",
                    "start_date_min",
                    "start_date_max",
                    "end_date_min",
                    "end_date_max",
                ),
        )

    private fun executeGammaListEvents(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/events",
            queryParameters =
                collectQueryParameters(
                    arguments,
                    "limit",
                    "offset",
                    "order",
                    "ascending",
                    "id",
                    "slug",
                    "active",
                    "closed",
                    "archived",
                    "tag_id",
                    "tag_slug",
                    "related_tags",
                    "liquidity_min",
                    "liquidity_max",
                    "volume_min",
                    "volume_max",
                    "start_date_min",
                    "start_date_max",
                    "end_date_min",
                    "end_date_max",
                ),
        )

    private fun executeGammaGetMarketBySlug(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/markets/slug/${requiredString(arguments, "slug")}",
            queryParameters = collectQueryParameters(arguments, "include_tag"),
        )

    private fun executeGammaGetEventBySlug(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/events/slug/${requiredString(arguments, "slug")}",
            queryParameters = collectQueryParameters(arguments, "include_chat", "include_template"),
        )

    private fun executeGammaSearch(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/public-search",
            queryParameters =
                collectQueryParameters(
                    arguments,
                    "q",
                    "cache",
                    "events_status",
                    "limit_per_type",
                    "page",
                    "events_tag",
                    "keep_closed_markets",
                    "sort",
                    "ascending",
                    "search_tags",
                    "search_profiles",
                ),
        )

    private fun executeGammaListMarketsKeyset(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/markets/keyset",
            queryParameters =
                collectQueryParameters(
                    arguments,
                    "limit",
                    "order",
                    "ascending",
                    "after_cursor",
                    "closed",
                    "id",
                    "slug",
                    "clob_token_ids",
                    "condition_ids",
                    "tag_id",
                    "related_tags",
                ),
        )

    private fun executeGammaListEventsKeyset(arguments: JsonObject): PolymarketHttpResponse =
        apiClient.gammaGet(
            path = "/events/keyset",
            queryParameters =
                collectQueryParameters(
                    arguments,
                    "limit",
                    "order",
                    "ascending",
                    "after_cursor",
                    "closed",
                    "live",
                    "featured",
                    "id",
                    "slug",
                    "tag_id",
                    "tag_slug",
                    "related_tags",
                ),
        )

    private fun executeClob(
        operation: String,
        arguments: JsonObject,
    ): PolymarketHttpResponse =
        when (operation) {
            "get_book" ->
                apiClient.clobGet(
                    path = "/book",
                    queryParameters = collectQueryParameters(arguments, "token_id"),
                )

            "get_price" ->
                apiClient.clobGet(
                    path = "/price",
                    queryParameters = collectQueryParameters(arguments, "token_id", "side"),
                )

            "get_midpoint" ->
                apiClient.clobGet(
                    path = "/midpoint",
                    queryParameters = collectQueryParameters(arguments, "token_id"),
                )

            "get_spread" ->
                apiClient.clobGet(
                    path = "/spread",
                    queryParameters = collectQueryParameters(arguments, "token_id"),
                )

            "get_last_trade_price" ->
                apiClient.clobGet(
                    path = "/last-trade-price",
                    queryParameters = collectQueryParameters(arguments, "token_id"),
                )

            "get_prices_history" ->
                apiClient.clobGet(
                    path = "/prices-history",
                    queryParameters = collectQueryParameters(arguments, "market", "startTs", "endTs", "interval", "fidelity"),
                )

            else -> throw IllegalArgumentException("Unsupported clob operation: $operation")
        }

    private fun executeData(
        operation: String,
        arguments: JsonObject,
    ): PolymarketHttpResponse =
        when (operation) {
            "get_trades" ->
                apiClient.dataGet(
                    path = "/trades",
                    queryParameters = collectQueryParameters(arguments, "limit", "offset", "takerOnly", "filterType", "filterAmount", "market", "eventId", "user", "side", "start", "end"),
                )

            "get_activity" ->
                apiClient.dataGet(
                    path = "/activity",
                    queryParameters =
                        collectQueryParameters(
                            arguments,
                            "user",
                            "limit",
                            "offset",
                            "market",
                            "eventId",
                            "type",
                            "start",
                            "end",
                            "sortBy",
                            "sortDirection",
                            "side",
                        ),
                )

            "get_positions" ->
                apiClient.dataGet(
                    path = "/positions",
                    queryParameters =
                        collectQueryParameters(
                            arguments,
                            "user",
                            "market",
                            "eventId",
                            "sizeThreshold",
                            "redeemable",
                            "mergeable",
                            "title",
                            "limit",
                            "offset",
                            "sortBy",
                            "sortDirection",
                        ),
                )

            "get_value" ->
                apiClient.dataGet(
                    path = "/value",
                    queryParameters = collectQueryParameters(arguments, "user", "market"),
                )

            "get_holders" ->
                apiClient.dataGet(
                    path = "/holders",
                    queryParameters = collectQueryParameters(arguments, "market", "limit", "minBalance"),
                )

            "get_open_interest" ->
                apiClient.dataGet(
                    path = "/oi",
                    queryParameters = collectQueryParameters(arguments, "market"),
                )

            "get_trader_leaderboard" ->
                apiClient.dataGet(
                    path = "/v1/leaderboard",
                    queryParameters = collectQueryParameters(arguments, "category", "timePeriod", "orderBy", "limit", "offset", "user", "userName"),
                )

            "get_builder_leaderboard" ->
                apiClient.dataGet(
                    path = "/v1/builders/leaderboard",
                    queryParameters = collectQueryParameters(arguments, "timePeriod", "limit", "offset"),
                )

            else -> throw IllegalArgumentException("Unsupported data operation: $operation")
        }

    private fun requiredString(
        arguments: JsonObject,
        fieldName: String,
    ): String = arguments.stringValue(fieldName) ?: throw IllegalArgumentException("Missing required field: $fieldName")

    private fun collectQueryParameters(
        arguments: JsonObject,
        vararg fieldNames: String,
    ): Map<String, String?> = fieldNames.associateWith { fieldName -> arguments.queryValue(fieldName) }
}

private fun JsonObject.stringValue(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.queryValue(name: String): String? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }

    val primitive = element.asJsonPrimitive
    return when {
        primitive.isString -> primitive.asString.trim().takeIf { it.isNotEmpty() }
        primitive.isBoolean -> primitive.asBoolean.toString()
        primitive.isNumber -> primitive.asNumber.toString()
        else -> null
    }
}

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
