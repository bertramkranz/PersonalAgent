package com.personalagent.bertbot.ingestion.connectors

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun interface ExternalChatAsyncRunner {
    fun submit(task: () -> Unit)
}

object DaemonThreadExternalChatAsyncRunner : ExternalChatAsyncRunner {
    private const val CORE_POOL_SIZE = 2
    private const val MAX_POOL_SIZE = 8
    private const val KEEP_ALIVE_SECONDS = 30L
    private const val MAX_QUEUE_SIZE = 256

    private val executor =
        ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(MAX_QUEUE_SIZE),
        ) { runnable ->
            Thread(runnable, "bertbot-external-chat-followup").apply {
                isDaemon = true
            }
        }.apply {
            rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
        }

    override fun submit(task: () -> Unit) {
        executor.execute(task)
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
