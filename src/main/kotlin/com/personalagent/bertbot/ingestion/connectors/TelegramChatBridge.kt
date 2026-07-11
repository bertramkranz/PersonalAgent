package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

class TelegramChatBridge(
    private val responder: (NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome,
) {
    fun handleUpdate(
        update: TelegramUpdatePayload,
        dryRun: Boolean = false,
    ): TelegramReplyPayload? {
        val inbound = TelegramNormalizer.normalize(update) ?: return null
        val outcome = responder(inbound, dryRun)
        val outbound = outcome.outbound ?: return null
        return OutboundMappers.toTelegram(outbound)
    }
}
