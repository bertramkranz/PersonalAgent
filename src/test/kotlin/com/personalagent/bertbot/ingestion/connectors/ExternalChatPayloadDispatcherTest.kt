package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.app.MacrofactorMcpTransport
import com.personalagent.bertbot.app.MacrofactorRuntimeConfiguration
import com.personalagent.bertbot.app.MacrofactorToolRouter
import com.personalagent.bertbot.app.buildRuntimeToolIntegrations
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalChatPayloadDispatcherTest {
    @Test
    fun `dispatcher returns macrofactor configured but unavailable response for telegram webhook`() {
        val macrofactorRouter =
            MacrofactorToolRouter(
                runtimeConfiguration =
                    MacrofactorRuntimeConfiguration(
                        enabled = true,
                        username = "tester",
                        password = "tester",
                        toolNamePrefix = "macrofactor_",
                    ),
                transport =
                    object : MacrofactorMcpTransport {
                        override fun listTools() = null

                        override fun callTool(
                            toolName: String,
                            arguments: JsonObject,
                        ): Pair<Boolean, String> = true to "unexpected"
                    },
            )
        val integration =
            buildRuntimeToolIntegrations(
                googleWorkspaceRouter = null,
                polymarketToolRouter = null,
                macrofactorToolRouter = macrofactorRouter,
            ).single()

        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    val params = JsonObject().apply { add("arguments", JsonObject()) }
                    val macrofactorOutcome = integration.toolExecutor("macrofactor_get_targets", params)
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = macrofactorOutcome?.second ?: "Tool not found",
                                replyToMessageId = inbound.messageId,
                            ),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
            )

        val rawJson =
            """
            {
              "update_id": 3004,
              "message": {
                "message_id": 91,
                "date": 1700000000,
                "chat": { "id": "chat-mf-unavailable" },
                "text": "get my macrofactor targets"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("sendMessage", parsed.get("method").asString)
        assertEquals("chat-mf-unavailable", parsed.get("chat_id").asString)
        assertContains(
            parsed.get("text").asString,
            "MacroFactor tool discovery failed",
        )
    }

    @Test
    fun `dispatcher returns macrofactor missing credentials response for telegram webhook`() {
        val macrofactorRouter =
            MacrofactorToolRouter(
                runtimeConfiguration =
                    MacrofactorRuntimeConfiguration(
                        enabled = true,
                        toolNamePrefix = "macrofactor_",
                    ),
            )
        val integration =
            buildRuntimeToolIntegrations(
                googleWorkspaceRouter = null,
                polymarketToolRouter = null,
                macrofactorToolRouter = macrofactorRouter,
            ).single()

        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    val params = JsonObject().apply { add("arguments", JsonObject()) }
                    val macrofactorOutcome = integration.toolExecutor("macrofactor_get_targets", params)
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = macrofactorOutcome?.second ?: "Tool not found",
                                replyToMessageId = inbound.messageId,
                            ),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
            )

        val rawJson =
            """
            {
              "update_id": 3003,
              "message": {
                "message_id": 90,
                "date": 1700000000,
                "chat": { "id": "chat-mf" },
                "text": "get my macrofactor targets"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("sendMessage", parsed.get("method").asString)
        assertEquals("chat-mf", parsed.get("chat_id").asString)
        assertContains(
            parsed.get("text").asString,
            "MacroFactor tools are enabled but missing credentials",
        )
    }

    @Test
    fun `dispatcher routes telegram webhook json and returns reply json`() {
        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = "**hi telegram** [View Event](https://example.com)",
                                replyToMessageId = inbound.messageId,
                            ),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
            )

        val rawJson =
            """
            {
              "update_id": 1001,
              "message": {
                "message_id": 55,
                "date": 1700000000,
                "chat": { "id": "chat-9" },
                "from": { "id": "u-1", "first_name": "Ada", "last_name": "L" },
                "text": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("sendMessage", parsed.get("method").asString)
        assertEquals("chat-9", parsed.get("chat_id").asString)
        assertNull(parsed.get("parse_mode"))
        assertEquals("**hi telegram** [View Event](https://example.com)", parsed.get("text").asString)
        val replyContext = parsed.get("reply_to_message_id")
        if (replyContext != null && !replyContext.isJsonNull) {
            assertTrue(replyContext.asString.isNotBlank())
        }
    }

    @Test
    fun `dispatcher passes telegram text through without markdown transformation`() {
        val telegramAdapter =
            TelegramConnectorAdapter(
                TelegramChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound =
                            NormalizedOutboundMessage(
                                source = inbound.source,
                                text = "# Upcoming\n  - First\n    - Nested\n  1. Ordered",
                            ),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(telegram = telegramAdapter),
            )

        val rawJson =
            """
            {
              "update_id": 2002,
              "message": {
                "message_id": 78,
                "date": 1700000000,
                "chat": { "id": "chat-10" },
                "text": "hello"
              }
            }
            """.trimIndent()

        val replyJson = dispatcher.handleTelegramUpdateJson(rawJson)

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertNull(parsed.get("parse_mode"))
        assertEquals("# Upcoming\n  - First\n    - Nested\n  1. Ordered", parsed.get("text").asString)
    }

    @Test
    fun `dispatcher routes slack webhook json and returns reply json`() {
        val slackAdapter =
            SlackConnectorAdapter(
                SlackChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "hi slack", threadId = inbound.threadId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(slack = slackAdapter),
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

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("C123", parsed.get("channel").asString)
        assertEquals("hi slack", parsed.get("text").asString)
        val threadContext = parsed.get("threadTs")
        if (threadContext != null && !threadContext.isJsonNull) {
            assertEquals("1700000000.100", threadContext.asString)
        }
    }

    @Test
    fun `dispatcher routes whatsapp webhook json and returns reply json`() {
        val whatsAppAdapter =
            WhatsAppConnectorAdapter(
                WhatsAppChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "hi whatsapp"),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(whatsapp = whatsAppAdapter),
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

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("15550000000", parsed.get("businessPhoneNumberId").asString)
        assertEquals("conv-2", parsed.get("conversationId").asString)
        assertEquals("hi whatsapp", parsed.get("text").asString)
    }

    @Test
    fun `dispatcher routes discord event json and returns reply json`() {
        val discordAdapter =
            DiscordConnectorAdapter(
                DiscordChatBridge { inbound, _ ->
                    ExternalChatOutcome(
                        inbound = inbound,
                        ingestion = IngestionOutcome(inbound, IngestionDecision.APPROVED),
                        outbound = NormalizedOutboundMessage(source = inbound.source, text = "hi discord", replyToMessageId = inbound.messageId),
                    )
                },
            )
        val dispatcher =
            ExternalChatPayloadDispatcher(
                connectors = BertBotExternalConnectors(discord = discordAdapter),
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

        assertNotNull(replyJson)
        val parsed = JsonParser.parseString(replyJson).asJsonObject
        assertEquals("ch-9", parsed.get("channelId").asString)
        assertEquals("hi discord", parsed.get("content").asString)
        assertEquals("m-1", parsed.get("messageReferenceId").asString)
    }

    @Test
    fun `dispatcher returns null for invalid payload`() {
        val dispatcher = ExternalChatPayloadDispatcher(connectors = BertBotExternalConnectors())

        val result = dispatcher.handleTelegramUpdateJson("not-json")

        assertNull(result)
    }

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
