package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.ConnectorConfig
import com.personalagent.bertbot.config.DiscordIntegrationConfig
import com.personalagent.bertbot.ingestion.connectors.DiscordMessagePayload
import com.personalagent.bertbot.ingestion.connectors.DiscordReplyPayload
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordBotMainTest {
    @Test
    fun `discord allowlist accepts guild channel when channel list is empty`() {
        val payload =
            DiscordMessagePayload(
                messageId = "m-1",
                channelId = "ch-1",
                guildId = "g-1",
                content = "hello",
            )
        val config =
            DiscordIntegrationConfig(
                connector = ConnectorConfig(enabled = true, approvalScope = "channel"),
                guildId = "g-1",
                approvedChannelIds = emptySet(),
            )

        assertTrue(discordAllowed(payload, config))
    }

    @Test
    fun `discord allowlist rejects mismatched guild`() {
        val payload =
            DiscordMessagePayload(
                messageId = "m-2",
                channelId = "ch-1",
                guildId = "g-2",
                content = "hello",
            )
        val config =
            DiscordIntegrationConfig(
                connector = ConnectorConfig(enabled = true, approvalScope = "channel"),
                guildId = "g-1",
                approvedChannelIds = emptySet(),
            )

        assertFalse(discordAllowed(payload, config))
    }

    @Test
    fun `discord allowlist requires channel membership when approved channels configured`() {
        val payload =
            DiscordMessagePayload(
                messageId = "m-3",
                channelId = "ch-denied",
                guildId = "g-1",
                content = "hello",
            )
        val config =
            DiscordIntegrationConfig(
                connector = ConnectorConfig(enabled = true, approvalScope = "channel"),
                guildId = "g-1",
                approvedChannelIds = setOf("ch-allowed"),
            )

        assertFalse(discordAllowed(payload, config))
    }

    @Test
    fun `discord allowlist uses dm channel list for direct messages`() {
        val payload =
            DiscordMessagePayload(
                messageId = "m-4",
                channelId = "dm-1",
                guildId = null,
                content = "hello",
            )
        val config =
            DiscordIntegrationConfig(
                connector = ConnectorConfig(enabled = true, approvalScope = "channel"),
                approvedDirectMessageIds = setOf("dm-1"),
            )

        assertTrue(discordAllowed(payload, config))
    }

    @Test
    fun `discord reply mode uses reply path when message reference matches inbound message`() {
        val mode =
            resolveDiscordReplyMode(
                inboundMessageId = "msg-1",
                reply = DiscordReplyPayload(channelId = "ch-1", content = "hello", messageReferenceId = "msg-1"),
            )

        assertTrue(mode == DiscordReplyMode.REPLY_TO_MESSAGE)
    }

    @Test
    fun `discord reply mode uses send path when message reference differs`() {
        val mode =
            resolveDiscordReplyMode(
                inboundMessageId = "msg-1",
                reply = DiscordReplyPayload(channelId = "ch-1", content = "hello", messageReferenceId = "msg-2"),
            )

        assertTrue(mode == DiscordReplyMode.SEND_TO_CHANNEL)
    }
}
