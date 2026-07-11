package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.JsonParser
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalChatPayloadDispatcherTest {
    @Test
    fun `dispatcher routes telegram webhook json and returns reply json`() {
        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "hi telegram", replyToMessageId = inbound.messageId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
            )

        val rawJson =
            """
            {
              "update_id": 1001,
              "message": {
                "message_id": 55,
                "date": 1700000000,
                "chat": { "id": "chat-9" },
                "from": { "id": "u-1", "first_name": "Ada", "last_name": "L" },
                "text": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("chat-9", parsed.get("chatId").asString)
        assertEquals("hi telegram", parsed.get("text").asString)
        val replyContext = parsed.get("replyToMessageId")
        if (replyContext != null && !replyContext.isJsonNull) {
            assertTrue(replyContext.asString.isNotBlank())
        }
    }

    @Test
    fun `dispatcher routes slack webhook json and returns reply json`() {
        val slackAdapter =
            SlackConnectorAdapter(
                SlackChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "hi slack", threadId = inbound.threadId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(slack = slackAdapter),
            )

        val rawJson =
            """
            {
              "team_id": "T001",
              "event": {
                "ts": "1700000000.222",
                "channel": "C123",
                "user": "U999",
                "text": "ping",
                "thread_ts": "1700000000.100"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleSlackEventJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("C123", parsed.get("channel").asString)
        assertEquals("hi slack", parsed.get("text").asString)
        val threadContext = parsed.get("threadTs")
        if (threadContext != null && !threadContext.isJsonNull) {
            assertEquals("1700000000.100", threadContext.asString)
        }
    }

    @Test
    fun `dispatcher routes whatsapp webhook json and returns reply json`() {
        val whatsAppAdapter =
            WhatsAppConnectorAdapter(
                WhatsAppChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "hi whatsapp"),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(whatsapp = whatsAppAdapter),
            )

        val rawJson =
            """
            {
              "business_phone_number_id": "15550000000",
              "conversation_id": "conv-2",
              "message": {
                "id": "wamid-abc",
                "from": "15551111111",
                "timestamp": 1700000000,
                "text_body": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleWhatsAppConversationJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("15550000000", parsed.get("businessPhoneNumberId").asString)
        assertEquals("conv-2", parsed.get("conversationId").asString)
        assertEquals("hi whatsapp", parsed.get("text").asString)
    }

    @Test
    fun `dispatcher returns null for invalid payload`() {
        val dispatcher = ExternalChatPayloadDispatcher(connectors = BertBotExternalConnectors())

        val result = dispatcher.handleTelegramUpdateJson("not-json")

        assertNull(result)
    }
}
