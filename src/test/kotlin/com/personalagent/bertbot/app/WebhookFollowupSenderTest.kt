package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import com.personalagent.bertbot.ingestion.connectors.BertBotExternalConnectors
import com.personalagent.bertbot.ingestion.connectors.ExternalChatAsyncRunner
import com.personalagent.bertbot.ingestion.connectors.ExternalChatPayloadDispatcher
import com.personalagent.bertbot.ingestion.connectors.TelegramChatBridge
import com.personalagent.bertbot.ingestion.connectors.TelegramConnectorAdapter
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookFollowupSenderTest {
    @Test
    fun `telegram follow-up sender posts final message to configured api base url`() {
        val receivedPath = AtomicReference<String?>(null)
        val receivedBody = AtomicReference<String?>(null)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bottest-token/sendMessage") { exchange ->
            receivedPath.set(exchange.requestURI.path)
            receivedBody.set(exchange.requestBody.bufferedReader().readText())
            val bytes = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val followupSender =
                createWebhookFollowupSender(
                    ExternalChatFollowupRuntimeConfig(
                        telegramBotToken = "test-token",
                        telegramApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                )
            val telegramAdapter =
                TelegramConnectorAdapter(
                    TelegramChatBridge { inbound, _ ->
                        ExternalChatOutcome(
                            inbound = inbound,
                            ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                            outbound = NormalizedOutboundMessage(source = inbound.source, text = "final from webhook", replyToMessageId = inbound.messageId),
                        )
                    },
                )
            val dispatcher =
                ExternalChatPayloadDispatcher(
                    connectors = BertBotExternalConnectors(telegram = telegramAdapter),
                    followupSender = followupSender,
                    asyncRunner = ExternalChatAsyncRunner { task -> task() },
                )

            val replyJson = dispatcher.handleTelegramUpdateJson(TELEGRAM_UPDATE_JSON)

            assertNotNull(replyJson)
            val parsed = JsonParser.parseString(replyJson).asJsonObject
            assertEquals("Working on it now. I will send the final answer shortly.", parsed.get("text").asString)
            assertEquals("/bottest-token/sendMessage", receivedPath.get())
            val posted = JsonParser.parseString(receivedBody.get()).asJsonObject
            assertEquals("chat-local", posted.get("chat_id").asString)
            assertEquals("final from webhook", posted.get("text").asString)
            assertTrue(posted.has("reply_to_message_id"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `telegram follow-up retries without reply target when initial send fails`() {
        val callCount = AtomicInteger(0)
        val firstBody = AtomicReference<String?>(null)
        val secondBody = AtomicReference<String?>(null)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/botretry-token/sendMessage") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val current = callCount.incrementAndGet()
            if (current == 1) {
                firstBody.set(body)
                val bytes = "{\"ok\":false,\"description\":\"Bad Request\"}".toByteArray()
                exchange.sendResponseHeaders(400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                secondBody.set(body)
                val bytes = "{\"ok\":true}".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()

        try {
            val followupSender =
                createWebhookFollowupSender(
                    ExternalChatFollowupRuntimeConfig(
                        telegramBotToken = "retry-token",
                        telegramApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                )
            val telegramAdapter =
                TelegramConnectorAdapter(
                    TelegramChatBridge { inbound, _ ->
                        ExternalChatOutcome(
                            inbound = inbound,
                            ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                            outbound = NormalizedOutboundMessage(source = inbound.source, text = "final retry", replyToMessageId = inbound.messageId),
                        )
                    },
                )
            val dispatcher =
                ExternalChatPayloadDispatcher(
                    connectors = BertBotExternalConnectors(telegram = telegramAdapter),
                    followupSender = followupSender,
                    asyncRunner = ExternalChatAsyncRunner { task -> task() },
                )

            val replyJson = dispatcher.handleTelegramUpdateJson(TELEGRAM_UPDATE_JSON)

            assertNotNull(replyJson)
            assertEquals(2, callCount.get())
            val firstPosted = JsonParser.parseString(firstBody.get()).asJsonObject
            assertTrue(firstPosted.has("reply_to_message_id"))
            val secondPosted = JsonParser.parseString(secondBody.get()).asJsonObject
            assertNull(secondPosted.get("reply_to_message_id"))
            assertEquals("final retry", secondPosted.get("text").asString)
        } finally {
            server.stop(0)
        }
    }
}

private val TELEGRAM_UPDATE_JSON =
    """
    {
      "update_id": 5001,
      "message": {
        "message_id": 123,
        "date": 1700000000,
        "chat": { "id": "chat-local" },
        "text": "hello"
      }
    }
    """.trimIndent()
