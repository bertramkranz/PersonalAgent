package com.personalagent.bertbot.app

import com.openai.models.ChatModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiRuntimeConfigurationTest {
    @Test
    fun `resolve configuration uses explicit environment values before dotenv`() {
        val configuration =
            resolveAiRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_AI_PROVIDER" to "openai",
                        "BERTBOT_AI_MODEL" to "gpt-4o",
                        "BERTBOT_AI_API_KEY" to "env-key",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_AI_PROVIDER" to "ignored",
                        "BERTBOT_AI_MODEL" to "ignored",
                        "BERTBOT_AI_API_KEY" to "dotenv-key",
                    ),
            )

        assertEquals("openai", configuration.provider)
        assertEquals("gpt-4o", configuration.model)
        assertEquals("env-key", configuration.apiKey)
    }

    @Test
    fun `resolve configuration falls back to dotenv values`() {
        val configuration =
            resolveAiRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_AI_PROVIDER" to "openai",
                        "BERTBOT_AI_MODEL" to "gpt-4o-mini",
                        "BERTBOT_AI_API_KEY" to "dotenv-key",
                    ),
            )

        assertEquals("openai", configuration.provider)
        assertEquals("gpt-4o-mini", configuration.model)
        assertEquals("dotenv-key", configuration.apiKey)
    }

    @Test
    fun `resolve configuration uses defaults when no overrides are provided`() {
        val configuration =
            resolveAiRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertEquals(DEFAULT_AI_PROVIDER, configuration.provider)
        assertEquals(DEFAULT_AI_MODEL, configuration.model)
        assertNull(configuration.apiKey)
    }

    @Test
    fun `openai model resolution preserves configured model name`() {
        val model = resolveOpenAiChatModel("gpt-4o")

        assertEquals(ChatModel.GPT_4O, model)
        assertEquals("gpt-4o", model.asString())
    }
}
