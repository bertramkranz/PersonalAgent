package com.personalagent.bertbot.ingestion.connectors

fun interface ExternalChatAsyncRunner {
    fun submit(task: () -> Unit)
}

object DaemonThreadExternalChatAsyncRunner : ExternalChatAsyncRunner {
    override fun submit(task: () -> Unit) {
        val worker = Thread(task, "bertbot-external-chat-followup")
        worker.isDaemon = true
        worker.start()
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
