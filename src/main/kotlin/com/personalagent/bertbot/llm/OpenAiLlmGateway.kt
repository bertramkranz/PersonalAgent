package com.personalagent.bertbot.llm

import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams

class OpenAiLlmGateway(
    private val service: OpenAIClient,
    private val model: ChatModel = ChatModel.GPT_4O_MINI,
) : LlmGateway {
    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val completionRequest =
            ChatCompletionCreateParams.builder()
                .model(model)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .build()

        val completion = service.chat().completions().create(completionRequest)
        return completion.choices().firstOrNull()?.message()?.content()?.orElse("") ?: ""
    }
}
