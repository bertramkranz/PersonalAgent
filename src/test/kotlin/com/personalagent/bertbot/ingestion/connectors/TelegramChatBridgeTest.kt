package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelegramChatBridgeTest {
    @Test
    fun `telegram chat bridge returns mapped reply payload`() {
        val bridge =
            TelegramChatBridge(
                responder = { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = "Hello from BertBot",
                                replyToMessageId = inbound.messageId,
                            ),
                    )
                },
            )

        val reply =
            bridge.handleUpdate(
                TelegramUpdatePayload(
                    updateId = "1",
                    message =
                        TelegramMessagePayload(
                            messageId = "42",
                            dateEpochSeconds = 1_700_000_000,
                            chat = TelegramChatPayload(id = "chat-77"),
                            text = "hello",
                        ),
                ),
            )

        assertNotNull(reply)
        assertEquals("chat-77", reply.chatId)
        assertEquals("42", reply.replyToMessageId)
        assertEquals("Hello from BertBot", reply.text)
    }

    @Test
    fun `telegram chat bridge returns null when update has no message`() {
        val bridge =
            TelegramChatBridge(
                responder = { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = IngestionSource(IngestionPlatform.TELEGRAM, IngestionSourceKind.CHAT, "chat-77"),
                                text = "unused",
                            ),
                    )
                },
            )

        val reply = bridge.handleUpdate(TelegramUpdatePayload(updateId = "2", message = null))

        assertNull(reply)
    }
}
