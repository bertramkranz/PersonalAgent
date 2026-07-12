package com.personalagent.bertbot.serialization

import com.google.gson.Gson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.lang.reflect.Type

interface AgentJsonCodec {
    fun encode(value: Any): String

    fun <T : Any> decode(
        payload: String,
        targetType: Class<T>,
    ): T?

    fun <T> decode(
        payload: String,
        targetType: Type,
    ): T?
}

class GsonAgentJsonCodec(
    private val gson: Gson = Gson(),
) : AgentJsonCodec {
    override fun encode(value: Any): String = gson.toJson(value)

    override fun <T : Any> decode(
        payload: String,
        targetType: Class<T>,
    ): T? = gson.fromJson(payload, targetType)

    override fun <T> decode(
        payload: String,
        targetType: Type,
    ): T? = gson.fromJson(payload, targetType)
}

class KotlinxAgentJsonCodec(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        },
    private val fallback: AgentJsonCodec = GsonAgentJsonCodec(),
) : AgentJsonCodec {
    override fun encode(value: Any): String {
        return if (value is JsonElement) {
            json.encodeToString(JsonElement.serializer(), value)
        } else {
            fallback.encode(value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decode(
        payload: String,
        targetType: Class<T>,
    ): T? {
        return if (targetType == JsonElement::class.java) {
            runCatching {
                json.parseToJsonElement(payload) as T
            }.getOrNull()
        } else {
            fallback.decode(payload, targetType)
        }
    }

    override fun <T> decode(
        payload: String,
        targetType: Type,
    ): T? = fallback.decode(payload, targetType)
}
