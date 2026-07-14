package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.util.logging.Logger

private const val EXTERNAL_CHAT_WORKING_STATUS_MESSAGE = "Working on it now. I will send the final answer shortly."
private const val EXTERNAL_CHAT_NO_FINAL_RESPONSE_MESSAGE = "I could not produce a final answer for that request. Please try again."
private val FOLLOWUP_LOGGER: Logger = Logger.getLogger(ExternalChatPayloadDispatcher::class.java.name)

class ExternalChatPayloadDispatcher(
    private val connectors: BertBotExternalConnectors,
    private val followupSender: ExternalChatFollowupSender = NoopExternalChatFollowupSender,
    private val asyncRunner: ExternalChatAsyncRunner = DaemonThreadExternalChatAsyncRunner,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) {
    fun handleTelegramUpdateJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.telegram ?: return null
        val update = parseTelegramUpdate(rawJson) ?: return null
        if (!followupSender.canSendTelegram()) {
            val reply = adapter.onUpdate(update, dryRun) ?: return null
            return buildTelegramInlineReply(reply)
        }

        val message = update.message ?: return null
        asyncRunner.submit {
            val finalReply =
                runCatching { adapter.onUpdate(update, dryRun) }
                    .onFailure { error ->
                        FOLLOWUP_LOGGER.warning("Failed to build Telegram follow-up response: ${error.message}")
                    }.getOrNull()
                    ?: TelegramReplyPayload(
                        chatId = message.chat.id,
                        text = EXTERNAL_CHAT_NO_FINAL_RESPONSE_MESSAGE,
                        replyToMessageId = message.messageId,
                    )
            runCatching { followupSender.sendTelegram(finalReply) }.onFailure { error ->
                FOLLOWUP_LOGGER.warning("Failed to send Telegram follow-up: ${error.message}")
            }
        }
        val status = TelegramReplyPayload(chatId = message.chat.id, text = EXTERNAL_CHAT_WORKING_STATUS_MESSAGE, replyToMessageId = message.messageId)
        return buildTelegramInlineReply(status)
    }

    fun handleSlackEventJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.slack ?: return null
        val event = parseSlackEnvelope(rawJson) ?: return null
        if (!followupSender.canSendSlack()) {
            val reply = adapter.onEvent(event, dryRun) ?: return null
            return codec.encode(reply)
        }

        val status = SlackReplyPayload(channel = event.event.channel, text = EXTERNAL_CHAT_WORKING_STATUS_MESSAGE, threadTs = event.event.threadTs)
        runCatching { followupSender.sendSlack(status) }.onFailure { error ->
            FOLLOWUP_LOGGER.warning("Failed to send Slack status message: ${error.message}")
        }
        asyncRunner.submit {
            val finalReply =
                runCatching { adapter.onEvent(event, dryRun) }
                    .onFailure { error ->
                        FOLLOWUP_LOGGER.warning("Failed to build Slack follow-up response: ${error.message}")
                    }.getOrNull()
                    ?: SlackReplyPayload(
                        channel = event.event.channel,
                        text = EXTERNAL_CHAT_NO_FINAL_RESPONSE_MESSAGE,
                        threadTs = event.event.threadTs,
                    )
            runCatching { followupSender.sendSlack(finalReply) }.onFailure { error ->
                FOLLOWUP_LOGGER.warning("Failed to send Slack follow-up: ${error.message}")
            }
        }
        return null
    }

    fun handleWhatsAppConversationJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.whatsapp ?: return null
        val event = parseWhatsAppConversation(rawJson) ?: return null
        if (!followupSender.canSendWhatsApp()) {
            val reply = adapter.onConversationEvent(event, dryRun) ?: return null
            return codec.encode(reply)
        }

        val status =
            WhatsAppReplyPayload(
                businessPhoneNumberId = event.businessPhoneNumberId,
                conversationId = event.conversationId,
                text = EXTERNAL_CHAT_WORKING_STATUS_MESSAGE,
                toPhoneNumber = event.message.from,
            )
        runCatching { followupSender.sendWhatsApp(status) }.onFailure { error ->
            FOLLOWUP_LOGGER.warning("Failed to send WhatsApp status message: ${error.message}")
        }
        asyncRunner.submit {
            val finalReply =
                runCatching { adapter.onConversationEvent(event, dryRun) }
                    .onFailure { error ->
                        FOLLOWUP_LOGGER.warning("Failed to build WhatsApp follow-up response: ${error.message}")
                    }.getOrNull()
                    ?: WhatsAppReplyPayload(
                        businessPhoneNumberId = event.businessPhoneNumberId,
                        conversationId = event.conversationId,
                        text = EXTERNAL_CHAT_NO_FINAL_RESPONSE_MESSAGE,
                        toPhoneNumber = event.message.from,
                    )
            runCatching { followupSender.sendWhatsApp(finalReply) }.onFailure { error ->
                FOLLOWUP_LOGGER.warning("Failed to send WhatsApp follow-up: ${error.message}")
            }
        }
        return null
    }

    fun handleDiscordMessageJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.discord ?: return null
        val event = parseDiscordMessage(rawJson) ?: return null
        if (!followupSender.canSendDiscord()) {
            val reply = adapter.onMessage(event, dryRun) ?: return null
            return codec.encode(reply)
        }

        val status = DiscordReplyPayload(channelId = event.channelId, content = EXTERNAL_CHAT_WORKING_STATUS_MESSAGE, messageReferenceId = event.messageId)
        runCatching { followupSender.sendDiscord(status) }.onFailure { error ->
            FOLLOWUP_LOGGER.warning("Failed to send Discord status message: ${error.message}")
        }
        asyncRunner.submit {
            val finalReply =
                runCatching { adapter.onMessage(event, dryRun) }
                    .onFailure { error ->
                        FOLLOWUP_LOGGER.warning("Failed to build Discord follow-up response: ${error.message}")
                    }.getOrNull()
                    ?: DiscordReplyPayload(
                        channelId = event.channelId,
                        content = EXTERNAL_CHAT_NO_FINAL_RESPONSE_MESSAGE,
                        messageReferenceId = event.messageId,
                    )
            runCatching { followupSender.sendDiscord(finalReply) }.onFailure { error ->
                FOLLOWUP_LOGGER.warning("Failed to send Discord follow-up: ${error.message}")
            }
        }
        return null
    }
}

data class BertBotExternalConnectors(
    val telegram: TelegramConnectorAdapter? = null,
    val slack: SlackConnectorAdapter? = null,
    val whatsapp: WhatsAppConnectorAdapter? = null,
    val discord: DiscordConnectorAdapter? = null,
)

private fun buildTelegramInlineReply(reply: TelegramReplyPayload): String {
    val obj = JsonObject()
    obj.addProperty("method", "sendMessage")
    obj.addProperty("chat_id", reply.chatId)
    obj.addProperty("text", normalizeOutboundText(reply.text))
    reply.replyToMessageId?.let { obj.addProperty("reply_to_message_id", it) }
    return obj.toString()
}

private fun normalizeOutboundText(text: String): String =
    text.replace("\r\n", "\n")

private fun parseTelegramUpdate(rawJson: String): TelegramUpdatePayload? {
    val root = rawJson.toJsonObjectOrNull() ?: return null
    val messageObject = root.objectValue("message") ?: return TelegramUpdatePayload(updateId = root.stringValue("update_id") ?: "unknown", message = null)
    val chatObject = messageObject.objectValue("chat") ?: return null
    val fromObject = messageObject.objectValue("from")

    val photos =
        messageObject.arrayValue("photo")
            .mapNotNull { element ->
                val photo = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val fileId = photo.stringValue("file_id") ?: return@mapNotNull null
                TelegramPhotoPayload(
                    fileId = fileId,
                    width = photo.intValue("width"),
                    height = photo.intValue("height"),
                )
            }

    val message =
        TelegramMessagePayload(
            messageId = messageObject.stringValue("message_id") ?: return null,
            dateEpochSeconds = messageObject.longValue("date") ?: return null,
            chat = TelegramChatPayload(id = chatObject.stringValue("id") ?: return null),
            from =
                if (fromObject == null) {
                    null
                } else {
                    TelegramUserPayload(
                        id = fromObject.stringValue("id") ?: return null,
                        username = fromObject.stringValue("username"),
                        firstName = fromObject.stringValue("first_name"),
                        lastName = fromObject.stringValue("last_name"),
                    )
                },
            text = messageObject.stringValue("text"),
            caption = messageObject.stringValue("caption"),
            photos = photos,
        )

    return TelegramUpdatePayload(
        updateId = root.stringValue("update_id") ?: "unknown",
        message = message,
    )
}

private fun parseSlackEnvelope(rawJson: String): SlackEnvelopePayload? {
    val root = rawJson.toJsonObjectOrNull() ?: return null
    val eventObject = root.objectValue("event") ?: return null

    val files =
        eventObject.arrayValue("files")
            .mapNotNull { element ->
                val file = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val id = file.stringValue("id") ?: return@mapNotNull null
                SlackFilePayload(
                    id = id,
                    name = file.stringValue("name"),
                    mimetype = file.stringValue("mimetype"),
                    urlPrivate = file.stringValue("url_private"),
                    size = file.longValue("size"),
                )
            }

    return SlackEnvelopePayload(
        teamId = root.stringValue("team_id") ?: return null,
        event =
            SlackMessageEventPayload(
                ts = eventObject.stringValue("ts") ?: return null,
                channel = eventObject.stringValue("channel") ?: return null,
                user = eventObject.stringValue("user"),
                text = eventObject.stringValue("text"),
                threadTs = eventObject.stringValue("thread_ts"),
                files = files,
            ),
    )
}

private fun parseWhatsAppConversation(rawJson: String): WhatsAppConversationPayload? {
    val root = rawJson.toJsonObjectOrNull() ?: return null
    val messageObject = root.objectValue("message") ?: return null
    val imageObject = messageObject.objectValue("image")

    return WhatsAppConversationPayload(
        businessPhoneNumberId = root.stringValue("business_phone_number_id") ?: return null,
        conversationId = root.stringValue("conversation_id") ?: return null,
        message =
            WhatsAppMessagePayload(
                id = messageObject.stringValue("id") ?: return null,
                from = messageObject.stringValue("from") ?: return null,
                timestampSeconds = messageObject.longValue("timestamp") ?: return null,
                textBody = messageObject.stringValue("text_body"),
                image =
                    if (imageObject == null) {
                        null
                    } else {
                        WhatsAppImagePayload(
                            id = imageObject.stringValue("id") ?: return null,
                            mimeType = imageObject.stringValue("mime_type"),
                        )
                    },
            ),
    )
}

private fun parseDiscordMessage(rawJson: String): DiscordMessagePayload? {
    val root = rawJson.toJsonObjectOrNull() ?: return null
    return DiscordMessagePayload(
        messageId = root.stringValue("message_id") ?: return null,
        channelId = root.stringValue("channel_id") ?: return null,
        guildId = root.stringValue("guild_id"),
        authorId = root.stringValue("author_id"),
        authorDisplayName = root.stringValue("author_display_name"),
        content = root.stringValue("content"),
        threadId = root.stringValue("thread_id"),
        timestampIso = root.stringValue("timestamp_iso"),
    )
}

private fun String.toJsonObjectOrNull(): JsonObject? =
    runCatching {
        JsonParser.parseString(this).asJsonObject
    }.getOrNull()

private fun JsonObject.stringValue(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive) {
        return null
    }
    val primitive = value.asJsonPrimitive
    return when {
        primitive.isString -> primitive.asString
        primitive.isNumber -> primitive.asNumber.toString()
        primitive.isBoolean -> primitive.asBoolean.toString()
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.longValue(name: String): Long? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive) {
        return null
    }
    return runCatching { value.asLong }.getOrNull()
}

private fun JsonObject.intValue(name: String): Int? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive) {
        return null
    }
    return runCatching { value.asInt }.getOrNull()
}

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

private fun JsonObject.arrayValue(name: String) =
    get(name)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.toList()
        .orEmpty()

private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
    if (isJsonObject) {
        asJsonObject
    } else {
        null
    }
