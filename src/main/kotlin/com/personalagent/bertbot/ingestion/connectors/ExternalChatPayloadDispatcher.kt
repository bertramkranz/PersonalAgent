package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec

class ExternalChatPayloadDispatcher(
    private val connectors: BertBotExternalConnectors,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) {
    fun handleTelegramUpdateJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.telegram ?: return null
        val update = parseTelegramUpdate(rawJson) ?: return null
        val reply = adapter.onUpdate(update, dryRun) ?: return null
        return buildTelegramInlineReply(reply)
    }

    fun handleSlackEventJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.slack ?: return null
        val event = parseSlackEnvelope(rawJson) ?: return null
        val reply = adapter.onEvent(event, dryRun) ?: return null
        return codec.encode(reply)
    }

    fun handleWhatsAppConversationJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.whatsapp ?: return null
        val event = parseWhatsAppConversation(rawJson) ?: return null
        val reply = adapter.onConversationEvent(event, dryRun) ?: return null
        return codec.encode(reply)
    }

    fun handleDiscordMessageJson(
        rawJson: String,
        dryRun: Boolean = false,
    ): String? {
        val adapter = connectors.discord ?: return null
        val event = parseDiscordMessage(rawJson) ?: return null
        val reply = adapter.onMessage(event, dryRun) ?: return null
        return codec.encode(reply)
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
    obj.addProperty("text", reply.text)
    reply.replyToMessageId?.let { obj.addProperty("reply_to_message_id", it) }
    return obj.toString()
}

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
