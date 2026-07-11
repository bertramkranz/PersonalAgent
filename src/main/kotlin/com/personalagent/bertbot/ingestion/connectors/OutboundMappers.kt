package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage

data class TelegramReplyPayload(
    val chatId: String,
    val text: String,
    val replyToMessageId: String? = null,
)

data class SlackReplyPayload(
    val channel: String,
    val text: String,
    val threadTs: String? = null,
)

data class WhatsAppReplyPayload(
    val businessPhoneNumberId: String,
    val conversationId: String,
    val text: String,
)

object OutboundMappers {
    fun toTelegram(message: NormalizedOutboundMessage): TelegramReplyPayload =
        TelegramReplyPayload(
            chatId = message.source.sourceId,
            text = message.text,
            replyToMessageId = message.replyToMessageId,
        )

    fun toSlack(message: NormalizedOutboundMessage): SlackReplyPayload =
        SlackReplyPayload(
            channel = message.source.sourceId,
            text = message.text,
            threadTs = message.threadId,
        )

    fun toWhatsApp(message: NormalizedOutboundMessage): WhatsAppReplyPayload {
        val phoneNumberId = message.source.workspaceId.orEmpty()
        return WhatsAppReplyPayload(
            businessPhoneNumberId = phoneNumberId,
            conversationId = message.source.sourceId,
            text = message.text,
        )
    }
}
