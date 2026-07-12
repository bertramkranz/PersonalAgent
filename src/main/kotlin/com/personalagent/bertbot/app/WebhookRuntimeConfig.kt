package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.ConnectorConfig
import com.personalagent.bertbot.config.IngestionConfig
import com.personalagent.bertbot.config.IngestionPolicyConfig
import com.personalagent.bertbot.config.SlackIntegrationConfig
import com.personalagent.bertbot.config.TelegramIntegrationConfig
import com.personalagent.bertbot.config.WhatsAppIntegrationConfig

internal fun resolveWebhookServerConfig(
    environment: Map<String, String> = System.getenv(),
): WebhookServerConfig {
    return WebhookServerConfig(
        host = environment["BERTBOT_WEBHOOK_HOST"]?.trim().takeUnless { it.isNullOrBlank() } ?: "0.0.0.0",
        port = environment["BERTBOT_WEBHOOK_PORT"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 8088,
        telegramPath = normalizeWebhookPath(environment["BERTBOT_WEBHOOK_TELEGRAM_PATH"], "/webhook/telegram"),
        slackPath = normalizeWebhookPath(environment["BERTBOT_WEBHOOK_SLACK_PATH"], "/webhook/slack"),
        whatsAppPath = normalizeWebhookPath(environment["BERTBOT_WEBHOOK_WHATSAPP_PATH"], "/webhook/whatsapp"),
        healthPath = normalizeWebhookPath(environment["BERTBOT_WEBHOOK_HEALTH_PATH"], "/health"),
        dryRun = environment["BERTBOT_WEBHOOK_DRY_RUN"].toBooleanEnv(defaultValue = false),
    )
}

internal fun resolveWebhookSecurityConfig(
    environment: Map<String, String> = System.getenv(),
): WebhookSecurityConfig {
    return WebhookSecurityConfig(
        requireSignatures = environment["BERTBOT_WEBHOOK_REQUIRE_SIGNATURES"].toBooleanEnv(defaultValue = false),
        trustProxyHeaders = environment["BERTBOT_WEBHOOK_TRUST_PROXY_HEADERS"].toBooleanEnv(defaultValue = false),
        allowedIpCidrs = parseCsvSet(environment["BERTBOT_WEBHOOK_ALLOWED_IPS"]),
        telegramSecretToken = environment["BERTBOT_TELEGRAM_SECRET_TOKEN"]?.trim()?.takeIf { it.isNotBlank() },
        slackSigningSecret = environment["BERTBOT_SLACK_SIGNING_SECRET"]?.trim()?.takeIf { it.isNotBlank() },
        slackMaxRequestAgeSeconds = environment["BERTBOT_SLACK_MAX_REQUEST_AGE_SECONDS"]?.toLongOrNull()?.coerceAtLeast(30) ?: 300,
        whatsAppAppSecret = environment["BERTBOT_WHATSAPP_APP_SECRET"]?.trim()?.takeIf { it.isNotBlank() },
        whatsAppVerifyToken = environment["BERTBOT_WHATSAPP_VERIFY_TOKEN"]?.trim()?.takeIf { it.isNotBlank() },
        rateLimitWindowSeconds = environment["BERTBOT_WEBHOOK_RATE_LIMIT_WINDOW_SECONDS"]?.toLongOrNull()?.coerceAtLeast(1) ?: 60,
        rateLimitMaxRequests = environment["BERTBOT_WEBHOOK_RATE_LIMIT_MAX_REQUESTS"]?.toIntOrNull()?.coerceAtLeast(1) ?: 120,
    )
}

internal fun resolveWebhookAgentConfig(
    environment: Map<String, String> = System.getenv(),
): BertBotAgentConfig {
    val enableTelegram = environment["BERTBOT_TELEGRAM_ENABLED"].toBooleanEnv(defaultValue = true)
    val enableSlack = environment["BERTBOT_SLACK_ENABLED"].toBooleanEnv(defaultValue = true)
    val enableWhatsApp = environment["BERTBOT_WHATSAPP_ENABLED"].toBooleanEnv(defaultValue = true)

    val ingestionEnabled = enableTelegram || enableSlack || enableWhatsApp
    return BertBotAgentConfig(
        ingestion =
            IngestionConfig(
                policy =
                    IngestionPolicyConfig(
                        enabled = ingestionEnabled,
                        storeImageReferencesOnly = true,
                        requireApproval = environment["BERTBOT_INGESTION_REQUIRE_APPROVAL"].toBooleanEnv(defaultValue = true),
                    ),
                telegram = TelegramIntegrationConfig(connector = ConnectorConfig(enabled = enableTelegram, approvalScope = "chat")),
                slack =
                    SlackIntegrationConfig(
                        connector = ConnectorConfig(enabled = enableSlack, approvalScope = "channel"),
                        workspaceId = environment["BERTBOT_SLACK_WORKSPACE_ID"]?.trim()?.takeIf { it.isNotBlank() },
                    ),
                whatsapp =
                    WhatsAppIntegrationConfig(
                        connector = ConnectorConfig(enabled = enableWhatsApp, approvalScope = "conversation"),
                        businessPhoneNumberId = environment["BERTBOT_WHATSAPP_BUSINESS_PHONE_ID"]?.trim()?.takeIf { it.isNotBlank() },
                    ),
            ),
    )
}

private fun normalizeWebhookPath(
    value: String?,
    fallback: String,
): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) {
        return fallback
    }
    return if (raw.startsWith('/')) raw else "/$raw"
}

private fun String?.toBooleanEnv(defaultValue: Boolean): Boolean {
    val value = this?.trim()?.lowercase() ?: return defaultValue
    return when (value) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}

private fun parseCsvSet(raw: String?): Set<String> =
    raw
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()
