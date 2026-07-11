package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

class SlackChatBridge(
    private val responder: (NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome,
) {
    fun handleEvent(
        payload: SlackEnvelopePayload,
        dryRun: Boolean = false,
    ): SlackReplyPayload? {
        val inbound = SlackNormalizer.normalize(payload)
        val outcome = responder(inbound, dryRun)
        val outbound = outcome.outbound ?: return null
        return OutboundMappers.toSlack(outbound)
    }
}
