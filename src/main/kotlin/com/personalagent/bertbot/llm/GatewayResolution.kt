package com.personalagent.bertbot.llm

data class GatewayResolution(
    val gateway: LlmGateway,
    val requestedModelId: String?,
    val effectiveModelId: String,
    val fallbackReason: String? = null,
)
