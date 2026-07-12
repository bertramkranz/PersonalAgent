package com.personalagent.bertbot.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object McpProtocolCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseRequest(rawMessage: String): McpJsonRpcRequest? {
        return try {
            val json = JsonParser.parseString(rawMessage).asJsonObject
            McpJsonRpcRequest(
                id = json.get("id"),
                method = json.get("method")?.asString,
                params = json.objectValue("params") ?: JsonObject(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun successResponse(
        requestId: JsonElement,
        result: JsonObject,
    ): String {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", requestId)
        response.add("result", result)
        return gson.toJson(response)
    }

    fun errorResponse(
        requestId: JsonElement?,
        code: Int,
        message: String,
    ): String {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", requestId ?: JsonNull.INSTANCE)

        val error = JsonObject()
        error.addProperty("code", code)
        error.addProperty("message", message)
        response.add("error", error)

        return gson.toJson(response)
    }

    fun toolResult(
        message: String,
        isError: Boolean,
    ): JsonObject {
        val result = JsonObject()
        val content = JsonArray()
        val textContent = JsonObject()
        textContent.addProperty("type", "text")
        textContent.addProperty("text", message)
        content.add(textContent)
        result.add("content", content)
        result.addProperty("isError", isError)
        return result
    }
}

internal data class McpJsonRpcRequest(
    val id: JsonElement?,
    val method: String?,
    val params: JsonObject,
)
