package com.personalagent.bertbot.ingestion.connectors

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

fun interface ExternalChatAsyncRunner {
    fun submit(task: () -> Unit)
}

object DaemonThreadExternalChatAsyncRunner : ExternalChatAsyncRunner {
    private val threadCounter = AtomicInteger(0)
    private val executor =
        Executors.newFixedThreadPool(2) { task ->
            Thread(task, "bertbot-external-chat-followup-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    override fun submit(task: () -> Unit) {
        executor.submit(task)
    }
}

interface ExternalChatFollowupSender {
    fun canSendTelegram(): Boolean = false

    fun canSendSlack(): Boolean = false

    fun canSendWhatsApp(): Boolean = false

    fun canSendDiscord(): Boolean = false

    fun sendTelegram(reply: TelegramReplyPayload) = Unit

    fun sendSlack(reply: SlackReplyPayload) = Unit

    fun sendWhatsApp(reply: WhatsAppReplyPayload) = Unit

    fun sendDiscord(reply: DiscordReplyPayload) = Unit
}

object NoopExternalChatFollowupSender : ExternalChatFollowupSender
