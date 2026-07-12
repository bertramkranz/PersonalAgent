package com.personalagent.bertbot.app

import com.google.gson.JsonElement

internal object McpRequestId {
    fun toSafeCorrelationId(requestId: JsonElement): String? {
        if (requestId.isJsonNull) {
            return null
        }

        return if (requestId.isJsonPrimitive) {
            requestId.asJsonPrimitive.toString().removeSurrounding("\"").trim().takeIf { it.isNotBlank() }
        } else {
            requestId.toString().trim().takeIf { it.isNotBlank() }
        }
    }
}
