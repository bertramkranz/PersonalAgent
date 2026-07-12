package com.personalagent.bertbot.agents

import com.google.gson.JsonElement
import com.google.gson.JsonParser

interface StructuredOutputGateway {
    fun parse(rawOutput: String): JsonElement
}

class JsonStructuredOutputGateway : StructuredOutputGateway {
    override fun parse(rawOutput: String): JsonElement {
        val normalized =
            rawOutput
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        return JsonParser.parseString(normalized)
    }
}

class KoogStructuredOutputGateway(
    private val delegate: StructuredOutputGateway = JsonStructuredOutputGateway(),
) : StructuredOutputGateway {
    override fun parse(rawOutput: String): JsonElement = delegate.parse(rawOutput)
}
