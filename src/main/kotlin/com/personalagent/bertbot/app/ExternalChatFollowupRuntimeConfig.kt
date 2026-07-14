package com.personalagent.bertbot.app

internal data class ExternalChatFollowupRuntimeConfig(
    val telegramBotToken: String? = null,
    val telegramApiBaseUrl: String = "https://api.telegram.org",
    val slackBotToken: String? = null,
    val whatsAppAccessToken: String? = null,
    val whatsAppApiVersion: String = "v22.0",
    val discordBotToken: String? = null,
)
