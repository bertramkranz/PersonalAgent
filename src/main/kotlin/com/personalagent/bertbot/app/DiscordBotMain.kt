package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.DiscordIntegrationConfig
import com.personalagent.bertbot.ingestion.connectors.DiscordMessagePayload
import com.personalagent.bertbot.ingestion.connectors.DiscordReplyPayload
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent

fun main() {
    val discordRuntimeConfig = resolveDiscordBotRuntimeConfig()
    if (!discordRuntimeConfig.enabled) {
        println("Discord bot runtime is disabled. Set BERTBOT_DISCORD_ENABLED=true to start it.")
        return
    }

    val token = discordRuntimeConfig.token
    if (token.isNullOrBlank()) {
        println("Discord bot token is missing. Set BERTBOT_DISCORD_BOT_TOKEN.")
        return
    }

    val agentConfig = resolveWebhookAgentConfig()
    val workspaceRoot = resolveWorkspaceRoot()
    val googleWorkspaceRuntime = resolveGoogleWorkspaceRuntimeConfiguration()
    val googleWorkspaceRouter = if (googleWorkspaceRuntime.enabled) GoogleWorkspaceToolRouter(googleWorkspaceRuntime) else null

    val runtime =
        BertBotRuntimeFactory.create(
            config = agentConfig,
            workspaceRoot = workspaceRoot,
            enablePeriodicResearchScheduler = true,
            googleWorkspaceRouter = googleWorkspaceRouter,
        )
    if (runtime == null) {
        printMissingApiKeyHelp()
        return
    }

    val listener = DiscordMessageListener(runtime, agentConfig)
    val jda =
        JDABuilder
            .createDefault(token)
            .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
            .addEventListeners(listener)
            .build()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { jda.shutdown() }
            runCatching { runtime.close() }
        },
    )

    jda.awaitReady()
    println("BertBot Discord bot started as ${jda.selfUser.name} (${jda.selfUser.id})")
}

private class DiscordMessageListener(
    private val runtime: BertBotRuntime,
    private val config: BertBotAgentConfig,
) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) {
            return
        }

        val discordConfig = config.ingestion.discord
        if (!discordConfig.connector.enabled) {
            return
        }

        val content = event.message.contentRaw.trim().takeIf { it.isNotBlank() } ?: return
        val payload =
            DiscordMessagePayload(
                messageId = event.messageId,
                channelId = event.channel.id,
                guildId = if (event.isFromGuild) event.guild.id else null,
                authorId = event.author.id,
                authorDisplayName = event.member?.effectiveName ?: event.author.name,
                content = content,
                threadId = event.channel.takeIf { it.type.isThread }?.id,
                timestampIso = event.message.timeCreated.toInstant().toString(),
            )

        if (!discordAllowed(payload, discordConfig)) {
            return
        }

        val adapter = runtime.connectors().discord ?: return
        val reply = adapter.onMessage(payload, discordConfig.connector.dryRunDefault) ?: return
        if (resolveDiscordReplyMode(event.messageId, reply) == DiscordReplyMode.REPLY_TO_MESSAGE) {
            event.message.reply(reply.content).queue()
        } else {
            event.channel.sendMessage(reply.content).queue()
        }
    }
}

internal enum class DiscordReplyMode {
    REPLY_TO_MESSAGE,
    SEND_TO_CHANNEL,
}

internal fun resolveDiscordReplyMode(
    inboundMessageId: String,
    reply: DiscordReplyPayload,
): DiscordReplyMode =
    if (reply.messageReferenceId == inboundMessageId) {
        DiscordReplyMode.REPLY_TO_MESSAGE
    } else {
        DiscordReplyMode.SEND_TO_CHANNEL
    }

internal fun discordAllowed(
    payload: DiscordMessagePayload,
    config: DiscordIntegrationConfig,
): Boolean {
    val configuredGuildId = config.guildId
    if (!configuredGuildId.isNullOrBlank() && payload.guildId != configuredGuildId) {
        return false
    }

    val isDirectMessage = payload.guildId.isNullOrBlank()
    return if (isDirectMessage) {
        config.approvedDirectMessageIds.isEmpty() || payload.channelId in config.approvedDirectMessageIds
    } else {
        config.approvedChannelIds.isEmpty() || payload.channelId in config.approvedChannelIds
    }
}
