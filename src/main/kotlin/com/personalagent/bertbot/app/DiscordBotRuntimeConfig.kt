package com.personalagent.bertbot.app

internal data class DiscordBotRuntimeConfig(
    val enabled: Boolean = false,
    val token: String? = null,
)
