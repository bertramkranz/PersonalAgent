package com.personalagent.bertbot.agents

import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.GatewayResolution
import com.personalagent.bertbot.llm.LlmGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SelfCorrectingSkillTest {
    @Test
    fun `self correcting skill returns parsed value on first valid output`() {
        val gateway = FakeLlmGateway(outputs = mutableListOf("42"))
        val skill =
            SelfCorrectingSkill(
                name = "integer_parser",
                llmGateway = gateway,
                outputFormatInstructions = "Return only an integer as plain text.",
                parser = { it.asString.trim().toInt() },
                maxAttempts = 3,
            )

        val result =
            skill.invoke(
                SelfCorrectingSkillRequest(systemPrompt = "System", userPrompt = "Give me an integer"),
                TracingContext(traceId = "test-trace"),
            )

        assertEquals(42, result)
        assertEquals(1, gateway.calls.size)
    }

    @Test
    fun `self correcting skill retries when parser fails and then succeeds`() {
        val gateway = FakeLlmGateway(outputs = mutableListOf("not-a-number", "100"))
        val skill =
            SelfCorrectingSkill(
                name = "integer_parser",
                llmGateway = gateway,
                outputFormatInstructions = "Return only an integer as plain text.",
                parser = { it.asString.trim().toInt() },
                maxAttempts = 3,
            )

        val result =
            skill.invoke(
                SelfCorrectingSkillRequest(systemPrompt = "System", userPrompt = "Give me an integer"),
                TracingContext(traceId = "test-trace"),
            )

        assertEquals(100, result)
        assertEquals(2, gateway.calls.size)
        assertTrue(gateway.calls[1].second.contains("did not match the required format", ignoreCase = true))
    }

    @Test
    fun `self correcting skill throws after max attempts`() {
        val gateway = FakeLlmGateway(outputs = mutableListOf("x", "y", "z"))
        val skill =
            SelfCorrectingSkill(
                name = "integer_parser",
                llmGateway = gateway,
                outputFormatInstructions = "Return only an integer as plain text.",
                parser = { it.asString.trim().toInt() },
                maxAttempts = 3,
            )

        val error =
            assertFailsWith<SelfCorrectionFailedException> {
                skill.invoke(
                    SelfCorrectingSkillRequest(systemPrompt = "System", userPrompt = "Give me an integer"),
                    TracingContext(traceId = "test-trace"),
                )
            }

        assertTrue(error.message?.contains("failed after 3 attempts") == true)
    }

    @Test
    fun `self correcting skill uses resolved gateway for selected model`() {
        val defaultGateway = FakeLlmGateway(outputs = mutableListOf("42"))
        val selectedGateway = FakeLlmGateway(outputs = mutableListOf("84"))
        val skill =
            SelfCorrectingSkill(
                name = "integer_parser",
                llmGateway = defaultGateway,
                outputFormatInstructions = "Return only an integer as plain text.",
                parser = { it.asString.trim().toInt() },
                maxAttempts = 3,
                gatewayResolver = { modelId ->
                    if (modelId == "reasoning") {
                        GatewayResolution(selectedGateway, modelId, "reasoning")
                    } else {
                        GatewayResolution(defaultGateway, modelId, "default")
                    }
                },
            )

        val result =
            skill.invoke(
                SelfCorrectingSkillRequest(systemPrompt = "System", userPrompt = "Give me an integer"),
                TracingContext(traceId = "test-trace"),
                selectedModelId = "reasoning",
            )

        assertEquals(84, result)
        assertEquals(1, selectedGateway.calls.size)
        assertEquals(0, defaultGateway.calls.size)
    }
}

private class FakeLlmGateway(
    private val outputs: MutableList<String>,
) : LlmGateway {
    val calls: MutableList<Pair<String, String>> = mutableListOf()

    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        calls.add(systemPrompt to userPrompt)
        return outputs.removeFirstOrNull() ?: ""
    }
}
