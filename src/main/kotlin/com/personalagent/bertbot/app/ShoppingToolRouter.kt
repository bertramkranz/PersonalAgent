package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val SHOPPING_TOOL_NAME = "shopping_query"

private val CONFIRMATION_REQUIRED_OPERATIONS = setOf("cart_prepare", "checkout_prepare")
private val READ_ONLY_OPERATIONS = setOf("search", "details", "compare")
private val ALLOWED_OPERATIONS = READ_ONLY_OPERATIONS + CONFIRMATION_REQUIRED_OPERATIONS

// Any operation that attempts final order placement is hard-blocked in all code paths.
private val BLOCKED_PLACE_ORDER_OPERATIONS =
    setOf("place_order", "submit_order", "confirm_order", "buy_now", "purchase")

internal class ShoppingToolRouter(
    private val config: ShoppingRuntimeConfiguration,
) {
    fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        if (toolName != SHOPPING_TOOL_NAME) return null

        val request = ToolInvocationRequestMapper.from(toolName, params)
        val arguments = request.arguments

        val operation =
            arguments.stringValue("operation")
                ?: return true to "Missing required field: operation"

        if (operation in BLOCKED_PLACE_ORDER_OPERATIONS) {
            return true to
                "Operation '$operation' is blocked. Final order placement is not permitted. " +
                "Use 'checkout_prepare' to produce a checkout summary for user review."
        }

        if (operation !in ALLOWED_OPERATIONS) {
            val allowed = ALLOWED_OPERATIONS.sorted().joinToString()
            return true to "Unknown operation: '$operation'. Allowed operations: $allowed"
        }

        if (operation in CONFIRMATION_REQUIRED_OPERATIONS) {
            val confirmationResult = enforceConfirmationAndGuardrails(operation, arguments)
            if (confirmationResult != null) return confirmationResult
        }

        return when (operation) {
            "search" -> handleSearch(arguments)
            "details" -> handleDetails(arguments)
            "compare" -> handleCompare(arguments)
            "cart_prepare" -> handleCartPrepare(arguments)
            "checkout_prepare" -> handleCheckoutPrepare(arguments)
            else -> true to "Unsupported operation: $operation"
        }
    }

    fun toolDefinitions(): List<JsonObject> = listOf(buildShoppingToolDefinition())

    private fun enforceConfirmationAndGuardrails(
        operation: String,
        arguments: JsonObject,
    ): Pair<Boolean, String>? {
        val token = arguments.stringValue("confirmation_token")
        if (token == null) {
            return true to
                "Operation '$operation' requires explicit user confirmation. " +
                "Provide a non-empty 'confirmation_token' obtained from the user before proceeding."
        }

        val priceCents = shoppingLongValue(arguments, "price_cents")
        if (priceCents != null && priceCents > config.budgetLimitCents) {
            return true to
                "Offer rejected: price ${priceCents}c exceeds budget limit ${config.budgetLimitCents}c. " +
                "Choose a lower-priced offer."
        }

        val sellerTrust = shoppingDoubleValue(arguments, "seller_trust_score")
        if (sellerTrust != null && sellerTrust < config.minSellerTrustScore) {
            return true to
                "Offer rejected: seller trust score $sellerTrust is below the minimum threshold " +
                "${config.minSellerTrustScore}. Choose a seller with a higher trust score."
        }

        return null
    }

    private fun handleSearch(arguments: JsonObject): Pair<Boolean, String> {
        val query =
            arguments.stringValue("query")
                ?: return true to "Missing required field: query"
        return false to
            "operation=search query=\"$query\" " +
            "budget_limit=${config.budgetLimitCents}c min_seller_trust=${config.minSellerTrustScore}"
    }

    private fun handleDetails(arguments: JsonObject): Pair<Boolean, String> {
        val itemId =
            arguments.stringValue("item_id")
                ?: return true to "Missing required field: item_id"
        return false to "operation=details item_id=\"$itemId\""
    }

    private fun handleCompare(arguments: JsonObject): Pair<Boolean, String> {
        val itemId =
            arguments.stringValue("item_id")
                ?: return true to "Missing required field: item_id"
        return false to
            "operation=compare item_id=\"$itemId\" " +
            "budget_limit=${config.budgetLimitCents}c min_seller_trust=${config.minSellerTrustScore}"
    }

    private fun handleCartPrepare(arguments: JsonObject): Pair<Boolean, String> {
        val itemId =
            arguments.stringValue("item_id")
                ?: return true to "Missing required field: item_id"
        return false to "operation=cart_prepare item_id=\"$itemId\" status=ready_for_user_review note=no_order_placed"
    }

    private fun handleCheckoutPrepare(arguments: JsonObject): Pair<Boolean, String> {
        val itemId =
            arguments.stringValue("item_id")
                ?: return true to "Missing required field: item_id"
        return false to "operation=checkout_prepare item_id=\"$itemId\" status=checkout_summary_ready note=no_order_placed"
    }
}

private fun shoppingLongValue(
    source: JsonObject,
    name: String,
): Long? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asLong }.getOrNull()
}

private fun shoppingDoubleValue(
    source: JsonObject,
    name: String,
): Double? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asDouble }.getOrNull()
}

private fun buildShoppingToolDefinition(): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", SHOPPING_TOOL_NAME)
    tool.addProperty(
        "description",
        "Shopping operations: search products, view details, compare offers, prepare cart, and prepare checkout. " +
            "Final order placement is not supported.",
    )

    val schema = JsonObject()
    schema.addProperty("type", "object")

    val properties = JsonObject()

    val operationProp = JsonObject()
    operationProp.addProperty("type", "string")
    operationProp.addProperty(
        "description",
        "Operation: search, details, compare, cart_prepare, checkout_prepare",
    )
    val operationEnum = JsonArray()
    ALLOWED_OPERATIONS.sorted().forEach { operationEnum.add(it) }
    operationProp.add("enum", operationEnum)
    properties.add("operation", operationProp)

    addStringProperty(properties, "query", "Search query text. Required for operation=search.")
    addStringProperty(properties, "item_id", "Item or offer identifier. Required for details, compare, cart_prepare, checkout_prepare.")
    addStringProperty(
        properties,
        "confirmation_token",
        "Explicit user confirmation token. Required for cart_prepare and checkout_prepare.",
    )
    addNumberProperty(
        properties,
        "price_cents",
        "Offer price in cents. Validated against the configured budget limit for cart_prepare and checkout_prepare.",
    )
    addNumberProperty(
        properties,
        "seller_trust_score",
        "Seller trust score in [0.0, 1.0]. Validated against the minimum threshold for cart_prepare and checkout_prepare.",
    )

    val required = JsonArray()
    required.add("operation")
    schema.add("properties", properties)
    schema.add("required", required)
    tool.add("inputSchema", schema)
    return tool
}

private fun addStringProperty(
    properties: JsonObject,
    name: String,
    description: String,
) {
    val prop = JsonObject()
    prop.addProperty("type", "string")
    prop.addProperty("description", description)
    properties.add(name, prop)
}

private fun addNumberProperty(
    properties: JsonObject,
    name: String,
    description: String,
) {
    val prop = JsonObject()
    prop.addProperty("type", "number")
    prop.addProperty("description", description)
    properties.add(name, prop)
}
