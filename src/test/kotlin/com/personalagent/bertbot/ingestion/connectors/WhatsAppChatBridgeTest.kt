package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WhatsAppChatBridgeTest {
    @Test
    fun `whatsapp chat bridge maps reply with business conversation ids`() {
        val bridge =
            WhatsAppChatBridge(
                responder = { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = "Acknowledged",
                            ),
                    )
                },
            )

        val reply =
            bridge.handleConversation(
                WhatsAppConversationPayload(
                    businessPhoneNumberId = "15550000000",
                    conversationId = "conv-1",
                    message =
                        WhatsAppMessagePayload(
                            id = "wamid-1",
                            from = "15551111111",
                            timestampSeconds = 1_700_000_000,
                            textBody = "hello",
                        ),
                ),
            )

        assertNotNull(reply)
        assertEquals("15550000000", reply.businessPhoneNumberId)
        assertEquals("conv-1", reply.conversationId)
        assertEquals("Acknowledged", reply.text)
    }
}
