package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

class WhatsAppChatBridge(
    private val responder: (NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome,
) {
    fun handleConversation(
        payload: WhatsAppConversationPayload,
        dryRun: Boolean = false,
    ): WhatsAppReplyPayload? {
        val inbound = WhatsAppNormalizer.normalize(payload)
        val outcome = responder(inbound, dryRun)
        val outbound = outcome.outbound ?: return null
        return OutboundMappers.toWhatsApp(outbound)
    }
}
