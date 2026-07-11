package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SlackChatBridgeTest {
    @Test
    fun `slack chat bridge preserves channel and thread in reply`() {
        val bridge =
            SlackChatBridge(
                responder = { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = "BertBot reply",
                                threadId = inbound.threadId,
                            ),
                    )
                },
            )

        val reply =
            bridge.handleEvent(
                SlackEnvelopePayload(
                    teamId = "T001",
                    event =
                        SlackMessageEventPayload(
                            ts = "1700000000.500",
                            channel = "C321",
                            user = "U001",
                            text = "status?",
                            threadTs = "1700000000.100",
                        ),
                ),
            )

        assertNotNull(reply)
        assertEquals("C321", reply.channel)
        assertEquals("1700000000.100", reply.threadTs)
        assertEquals("BertBot reply", reply.text)
    }
}
