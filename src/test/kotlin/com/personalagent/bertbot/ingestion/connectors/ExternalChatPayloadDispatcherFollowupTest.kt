package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.JsonParser
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExternalChatPayloadDispatcherFollowupTest {
    @Test
    fun `dispatcher sends telegram status immediately and final via followup sender`() {
        val sender = RecordingFollowupSender(enableTelegram = true)
        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "final telegram", replyToMessageId = inbound.messageId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "update_id": 4001,
              "message": {
                "message_id": 99,
                "date": 1700000000,
                "chat": { "id": "chat-async" },
                "text": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("Working on it now. I will send the final answer shortly.", parsed.get("text").asString)
        assertEquals(1, sender.telegramReplies.size)
        assertEquals("final telegram", sender.telegramReplies.first().text)
    }

    @Test
    fun `dispatcher sends telegram fallback final when adapter returns null in followup mode`() {
        val sender = RecordingFollowupSender(enableTelegram = true)
        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = null,
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "update_id": 4005,
              "message": {
                "message_id": 99,
                "date": 1700000000,
                "chat": { "id": "chat-async" },
                "text": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        assertEquals(1, sender.telegramReplies.size)
        assertEquals("I could not produce a final answer for that request. Please try again.", sender.telegramReplies.first().text)
    }

    @Test
    fun `dispatcher sends slack status and final via followup sender`() {
        val sender = RecordingFollowupSender(enableSlack = true)
        val slackAdapter =
            SlackConnectorAdapter(
                SlackChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "final slack", threadId = inbound.threadId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(slack = slackAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "team_id": "T001",
              "event": {
                "ts": "1700000000.222",
                "channel": "C123",
                "user": "U999",
                "text": "ping",
                "thread_ts": "1700000000.100"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleSlackEventJson(rawJson)

        assertNull(replyJson)
        assertEquals(2, sender.slackReplies.size)
        assertEquals("Working on it now. I will send the final answer shortly.", sender.slackReplies.first().text)
        assertEquals("final slack", sender.slackReplies.last().text)
    }

    @Test
    fun `dispatcher sends slack fallback final when adapter returns null in followup mode`() {
        val sender = RecordingFollowupSender(enableSlack = true)
        val slackAdapter =
            SlackConnectorAdapter(
                SlackChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = null,
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(slack = slackAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "team_id": "T001",
              "event": {
                "ts": "1700000000.222",
                "channel": "C123",
                "user": "U999",
                "text": "ping",
                "thread_ts": "1700000000.100"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleSlackEventJson(rawJson)

        assertNull(replyJson)
        assertEquals(2, sender.slackReplies.size)
        assertEquals("I could not produce a final answer for that request. Please try again.", sender.slackReplies.last().text)
    }

    @Test
    fun `dispatcher sends whatsapp status and final via followup sender`() {
        val sender = RecordingFollowupSender(enableWhatsApp = true)
        val whatsAppAdapter =
            WhatsAppConnectorAdapter(
                WhatsAppChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "final whatsapp"),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(whatsapp = whatsAppAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "business_phone_number_id": "15550000000",
              "conversation_id": "conv-2",
              "message": {
                "id": "wamid-abc",
                "from": "15551111111",
                "timestamp": 1700000000,
                "text_body": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleWhatsAppConversationJson(rawJson)

        assertNull(replyJson)
        assertEquals(2, sender.whatsAppReplies.size)
        assertEquals("Working on it now. I will send the final answer shortly.", sender.whatsAppReplies.first().text)
        assertEquals("final whatsapp", sender.whatsAppReplies.last().text)
        assertEquals("15551111111", sender.whatsAppReplies.last().toPhoneNumber)
    }

    @Test
    fun `dispatcher sends whatsapp fallback final when adapter returns null in followup mode`() {
        val sender = RecordingFollowupSender(enableWhatsApp = true)
        val whatsAppAdapter =
            WhatsAppConnectorAdapter(
                WhatsAppChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = null,
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(whatsapp = whatsAppAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "business_phone_number_id": "15550000000",
              "conversation_id": "conv-2",
              "message": {
                "id": "wamid-abc",
                "from": "15551111111",
                "timestamp": 1700000000,
                "text_body": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleWhatsAppConversationJson(rawJson)

        assertNull(replyJson)
        assertEquals(2, sender.whatsAppReplies.size)
        assertEquals("I could not produce a final answer for that request. Please try again.", sender.whatsAppReplies.last().text)
        assertEquals("15551111111", sender.whatsAppReplies.last().toPhoneNumber)
    }

    @Test
    fun `dispatcher sends discord status and final via followup sender`() {
        val sender = RecordingFollowupSender(enableDiscord = true)
        val discordAdapter =
            DiscordConnectorAdapter(
                DiscordChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "final discord", replyToMessageId = inbound.messageId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(discord = discordAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "message_id": "m-1",
              "channel_id": "ch-9",
              "guild_id": "g-1",
              "author_id": "u-2",
              "author_display_name": "Ada",
              "content": "hello",
              "thread_id": "t-7",
              "timestamp_iso": "2026-07-12T10:15:30Z"
            }
            """.trimIndent()

        val replyJson = dispatcher.handleDiscordMessageJson(rawJson)

        assertNull(replyJson)
        assertEquals(2, sender.discordReplies.size)
        assertEquals("Working on it now. I will send the final answer shortly.", sender.discordReplies.first().content)
        assertEquals("final discord", sender.discordReplies.last().content)
    }

    @Test
    fun `dispatcher sends discord fallback final when adapter returns null in followup mode`() {
        val sender = RecordingFollowupSender(enableDiscord = true)
        val discordAdapter =
            DiscordConnectorAdapter(
                DiscordChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = null,
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(discord = discordAdapter),
                followupSender = sender,
                asyncRunner = ExternalChatAsyncRunner { task -> task() },
            )

        val rawJson =
            """
            {
              "message_id": "m-1",
              "channel_id": "ch-9",
              "guild_id": "g-1",
              "author_id": "u-2",
              "author_display_name": "Ada",
              "content": "hello",
              "thread_id": "t-7",
              "timestamp_iso": "2026-07-12T10:15:30Z"
            }
            """.trimIndent()

        val replyJson = dispatcher.handleDiscordMessageJson(rawJson)

        assertNull(replyJson)
        assertEquals(2, sender.discordReplies.size)
        assertEquals("I could not produce a final answer for that request. Please try again.", sender.discordReplies.last().content)
    }
}

private class RecordingFollowupSender(
    private val enableTelegram: Boolean = false,
    private val enableSlack: Boolean = false,
    private val enableWhatsApp: Boolean = false,
    private val enableDiscord: Boolean = false,
) : ExternalChatFollowupSender {
    val telegramReplies = mutableListOf<TelegramReplyPayload>()
    val slackReplies = mutableListOf<SlackReplyPayload>()
    val whatsAppReplies = mutableListOf<WhatsAppReplyPayload>()
    val discordReplies = mutableListOf<DiscordReplyPayload>()

    override fun canSendTelegram(): Boolean = enableTelegram

    override fun canSendSlack(): Boolean = enableSlack

    override fun canSendWhatsApp(): Boolean = enableWhatsApp

    override fun canSendDiscord(): Boolean = enableDiscord

    override fun sendTelegram(reply: TelegramReplyPayload) {
        telegramReplies += reply
    }

    override fun sendSlack(reply: SlackReplyPayload) {
        slackReplies += reply
    }

    override fun sendWhatsApp(reply: WhatsAppReplyPayload) {
        whatsAppReplies += reply
    }

    override fun sendDiscord(reply: DiscordReplyPayload) {
        discordReplies += reply
    }
}
