package com.personalagent.bertbot.llm

interface LlmGateway {
    fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String
}
