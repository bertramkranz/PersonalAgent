package com.personalagent.bertbot.app

import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal data class McpJsonRpcRequestDto(
    val jsonrpc: String? = null,
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonObject = JsonObject(),
)

internal data class McpJsonRpcResponseDto(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonObject? = null,
    val error: McpJsonRpcErrorDto? = null,
)

internal data class McpJsonRpcErrorDto(
    val code: Int,
    val message: String,
)

internal data class McpToolResultDto(
    val message: String,
    val isError: Boolean,
)
