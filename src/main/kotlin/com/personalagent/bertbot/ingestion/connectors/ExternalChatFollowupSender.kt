package com.personalagent.bertbot.ingestion.connectors

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun interface ExternalChatAsyncRunner {
    fun submit(task: () -> Unit)
}

object DaemonThreadExternalChatAsyncRunner : ExternalChatAsyncRunner {
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("bertbot-external-chat-followup"),
        )

    override fun submit(task: () -> Unit) {
        scope.launch {
            task()
        }
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
