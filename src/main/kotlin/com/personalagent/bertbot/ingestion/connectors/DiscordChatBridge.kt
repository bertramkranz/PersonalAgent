package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

class DiscordChatBridge(
    private val responder: (NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome,
) {
    fun handleMessage(
        payload: DiscordMessagePayload,
        dryRun: Boolean = false,
    ): DiscordReplyPayload? {
        val inbound = DiscordNormalizer.normalize(payload)
        val outcome = responder(inbound, dryRun)
        val outbound = outcome.outbound ?: return null
        return OutboundMappers.toDiscord(outbound)
    }
}
