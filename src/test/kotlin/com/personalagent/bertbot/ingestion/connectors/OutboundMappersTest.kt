package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboundMappersTest {
    @Test
    fun `telegram mapper preserves chat and reply ids`() {
        val outbound =
            NormalizedOutboundMessage(
                source = IngestionSource(IngestionPlatform.TELEGRAM, IngestionSourceKind.CHAT, "chat-1"),
                text = "hello",
                replyToMessageId = "m-1",
            )

        val payload = OutboundMappers.toTelegram(outbound)

        assertEquals("chat-1", payload.chatId)
        assertEquals("m-1", payload.replyToMessageId)
        assertEquals("hello", payload.text)
    }

    @Test
    fun `slack mapper preserves thread context`() {
        val outbound =
            NormalizedOutboundMessage(
                source = IngestionSource(IngestionPlatform.SLACK, IngestionSourceKind.CHANNEL, "C123", "T1"),
                text = "done",
                threadId = "1700000000.100",
            )

        val payload = OutboundMappers.toSlack(outbound)

        assertEquals("C123", payload.channel)
        assertEquals("1700000000.100", payload.threadTs)
    }

    @Test
    fun `whatsapp mapper keeps business phone id and conversation id`() {
        val outbound =
            NormalizedOutboundMessage(
                source = IngestionSource(IngestionPlatform.WHATSAPP, IngestionSourceKind.BUSINESS_CONVERSATION, "conv-9", "15550000000"),
                text = "ack",
            )

        val payload = OutboundMappers.toWhatsApp(outbound)

        assertEquals("15550000000", payload.businessPhoneNumberId)
        assertEquals("conv-9", payload.conversationId)
        assertEquals("ack", payload.text)
    }
}
