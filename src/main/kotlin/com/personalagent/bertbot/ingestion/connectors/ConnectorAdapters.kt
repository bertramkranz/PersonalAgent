package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

interface ExternalChatResponder {
    fun respond(
        message: NormalizedIngestionMessage,
        dryRun: Boolean = false,
    ): ExternalChatOutcome
}

class LambdaExternalChatResponder(
    private val block: (NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome,
) : ExternalChatResponder {
    override fun respond(
        message: NormalizedIngestionMessage,
        dryRun: Boolean,
    ): ExternalChatOutcome = block(message, dryRun)
}

class TelegramConnectorAdapter(
    private val bridge: TelegramChatBridge,
) {
    fun onUpdate(
        update: TelegramUpdatePayload,
        dryRun: Boolean = false,
    ): TelegramReplyPayload? = bridge.handleUpdate(update, dryRun)
}

class SlackConnectorAdapter(
    private val bridge: SlackChatBridge,
) {
    fun onEvent(
        payload: SlackEnvelopePayload,
        dryRun: Boolean = false,
    ): SlackReplyPayload? = bridge.handleEvent(payload, dryRun)
}

class WhatsAppConnectorAdapter(
    private val bridge: WhatsAppChatBridge,
) {
    fun onConversationEvent(
        payload: WhatsAppConversationPayload,
        dryRun: Boolean = false,
    ): WhatsAppReplyPayload? = bridge.handleConversation(payload, dryRun)
}

class DiscordConnectorAdapter(
    private val bridge: DiscordChatBridge,
) {
    fun onMessage(
        payload: DiscordMessagePayload,
        dryRun: Boolean = false,
    ): DiscordReplyPayload? = bridge.handleMessage(payload, dryRun)
}
