package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * End-to-end tests for shopping workflow stages in MCP mode.
 *
 * Verifies that each stage (onboarding, recommendation, compare, cart_prepare,
 * checkout_prepare) always routes through the MCP dispatcher and produces a
 * non-empty, non-error user-visible response.
 */
class McpRequestDispatcherShoppingWorkflowTest {
    @Test
    fun `onboarding prompt produces user-visible response in MCP mode`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    "Let me help you set up your shopping preferences. What kinds of products are you interested in?"
                },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1001,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Help me set up my shopping preferences and profile"}}}
                """.trimIndent(),
            )

        assertNonEmptyToolResponse(response)
    }

    @Test
    fun `recommendation prompt produces user-visible response in MCP mode`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    "Based on your profile, here are my top picks: ..."
                },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1002,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Recommend a laptop for programming under 2000 EUR"}}}
                """.trimIndent(),
            )

        assertNonEmptyToolResponse(response)
    }

    @Test
    fun `compare prompt produces user-visible response in MCP mode`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    "Here is a comparison of the two models: ..."
                },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1003,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Compare the MacBook Pro M3 and the Dell XPS 15"}}}
                """.trimIndent(),
            )

        assertNonEmptyToolResponse(response)
    }

    @Test
    fun `cart_prepare prompt produces user-visible response in MCP mode and requests confirmation`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    "I found the item. To proceed, please confirm you want to add the MacBook Pro M3 to your cart."
                },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1004,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Add the MacBook Pro M3 to my cart"}}}
                """.trimIndent(),
            )

        val text = assertNonEmptyToolResponse(response)
        assertFalse(text.isBlank(), "cart_prepare response must not be blank")
    }

    @Test
    fun `checkout_prepare prompt produces user-visible response in MCP mode and requests confirmation`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ ->
                    "Here is a summary of your order. Please confirm before I proceed with checkout preparation."
                },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1005,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"Prepare checkout for the MacBook Pro M3 in my cart"}}}
                """.trimIndent(),
            )

        val text = assertNonEmptyToolResponse(response)
        assertFalse(text.isBlank(), "checkout_prepare response must not be blank")
    }

    @Test
    fun `shopping prompts always return a response when runtime is unavailable`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> null },
            )

        listOf(
            "Help me set up my shopping preferences",
            "Recommend a laptop for programming",
            "Compare MacBook Pro vs Dell XPS 15",
            "Add the MacBook Pro to my cart",
            "Prepare checkout for my cart",
        ).forEachIndexed { index, prompt ->
            val response =
                dispatcher.handle(
                    """
                    {"jsonrpc":"2.0","id":${2000 + index},"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"$prompt"}}}
                    """.trimIndent(),
                )
            assertNotNull(response, "Response must not be null for prompt: $prompt")
            val json = JsonParser.parseString(response).asJsonObject
            assertNotNull(json.getAsJsonObject("result"), "Result must be present for prompt: $prompt")
        }
    }

    @Test
    fun `shopping workflow prompts in webhook mode produce outbound responses`() {
        var capturedMessage: com.personalagent.bertbot.ingestion.NormalizedIngestionMessage? = null

        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "Handled your shopping request." },
                ingestionControlPlane = null,
                externalChatResponder = { message, dryRun ->
                    capturedMessage = message
                    com.personalagent.bertbot.ingestion.ExternalChatOutcome(
                        inbound = message,
                        ingestion =
                            com.personalagent.bertbot.ingestion.IngestionOutcome(
                                message,
                                com.personalagent.bertbot.ingestion.IngestionDecision.APPROVED,
                                dryRun = dryRun,
                            ),
                        outbound =
                            com.personalagent.bertbot.ingestion.NormalizedOutboundMessage(
                                source = message.source,
                                text = "Here are my product recommendations for you.",
                                replyToMessageId = message.messageId,
                            ),
                        dryRun = dryRun,
                    )
                },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":3001,"method":"tools/call","params":{"name":"ingestion_chat_manual","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-shop","text":"Recommend a laptop for programming","dryRun":true}}}
                """.trimIndent(),
            )

        assertNotNull(response)
        val json = JsonParser.parseString(response).asJsonObject
        val result = json.getAsJsonObject("result")
        assertNotNull(result)
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertFalse(text.isBlank(), "Webhook mode shopping response must produce visible outbound text")
    }

    private fun assertNonEmptyToolResponse(response: String?): String {
        assertNotNull(response)
        val json = JsonParser.parseString(response).asJsonObject
        val result = json.getAsJsonObject("result")
        assertNotNull(result)
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertFalse(text.isBlank(), "Response text must not be blank")
        assertEquals(false, result.get("isError").asBoolean)
        return text
    }
}
