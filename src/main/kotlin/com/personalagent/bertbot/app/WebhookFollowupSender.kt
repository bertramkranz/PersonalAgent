package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.personalagent.bertbot.ingestion.connectors.DiscordReplyPayload
import com.personalagent.bertbot.ingestion.connectors.ExternalChatFollowupSender
import com.personalagent.bertbot.ingestion.connectors.NoopExternalChatFollowupSender
import com.personalagent.bertbot.ingestion.connectors.SlackReplyPayload
import com.personalagent.bertbot.ingestion.connectors.TelegramReplyPayload
import com.personalagent.bertbot.ingestion.connectors.WhatsAppReplyPayload
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal fun createWebhookFollowupSender(config: ExternalChatFollowupRuntimeConfig): ExternalChatFollowupSender {
    if (!hasAnyFollowupCredential(config)) {
        return NoopExternalChatFollowupSender
    }
    return HttpExternalChatFollowupSender(config)
}

private fun hasAnyFollowupCredential(config: ExternalChatFollowupRuntimeConfig): Boolean {
    return listOf(config.telegramBotToken, config.slackBotToken, config.whatsAppAccessToken, config.discordBotToken)
        .any { value -> !value.isNullOrBlank() }
}

private class HttpExternalChatFollowupSender(
    private val config: ExternalChatFollowupRuntimeConfig,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : ExternalChatFollowupSender {
    override fun canSendTelegram(): Boolean = !config.telegramBotToken.isNullOrBlank()

    override fun canSendSlack(): Boolean = !config.slackBotToken.isNullOrBlank()

    override fun canSendWhatsApp(): Boolean = !config.whatsAppAccessToken.isNullOrBlank()

    override fun canSendDiscord(): Boolean = !config.discordBotToken.isNullOrBlank()

    override fun sendTelegram(reply: TelegramReplyPayload) {
        val token = config.telegramBotToken ?: return
        val payload =
            JsonObject().apply {
                addProperty("chat_id", reply.chatId)
                addProperty("text", reply.text)
                reply.replyToMessageId?.let { addProperty("reply_to_message_id", it) }
            }
        postJson(
            url = "https://api.telegram.org/bot$token/sendMessage",
            json = payload.toString(),
        )
    }

    override fun sendSlack(reply: SlackReplyPayload) {
        val token = config.slackBotToken ?: return
        val payload =
            JsonObject().apply {
                addProperty("channel", reply.channel)
                addProperty("text", reply.text)
                reply.threadTs?.let { addProperty("thread_ts", it) }
            }
        postJson(
            url = "https://slack.com/api/chat.postMessage",
            json = payload.toString(),
            bearerToken = token,
        )
    }

    override fun sendWhatsApp(reply: WhatsAppReplyPayload) {
        val token = config.whatsAppAccessToken ?: return
        val to = reply.toPhoneNumber?.takeIf { it.isNotBlank() } ?: return
        val payload =
            JsonObject().apply {
                addProperty("messaging_product", "whatsapp")
                addProperty("recipient_type", "individual")
                addProperty("to", to)
                add(
                    "text",
                    JsonObject().apply {
                        addProperty("preview_url", false)
                        addProperty("body", reply.text)
                    },
                )
            }
        val url = "https://graph.facebook.com/${config.whatsAppApiVersion}/${reply.businessPhoneNumberId}/messages"
        postJson(
            url = url,
            json = payload.toString(),
            bearerToken = token,
        )
    }

    override fun sendDiscord(reply: DiscordReplyPayload) {
        val token = config.discordBotToken ?: return
        val payload =
            JsonObject().apply {
                addProperty("content", reply.content)
                reply.messageReferenceId?.let { referenceId ->
                    add(
                        "message_reference",
                        JsonObject().apply {
                            addProperty("message_id", referenceId)
                        },
                    )
                }
            }
        val url = "https://discord.com/api/v10/channels/${reply.channelId}/messages"
        postJson(
            url = url,
            json = payload.toString(),
            bearerToken = token,
            authorizationScheme = "Bot",
        )
    }

    private fun postJson(
        url: String,
        json: String,
        bearerToken: String? = null,
        authorizationHeader: String? = null,
        authorizationScheme: String = "Bearer",
    ) {
        val builder =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
        val resolvedAuthorizationHeader =
            authorizationHeader?.takeIf { it.isNotBlank() }
                ?: bearerToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "$authorizationScheme $it" }
        resolvedAuthorizationHeader?.let { builder.header("Authorization", it) }

        runCatching {
            client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        }
    }
}
