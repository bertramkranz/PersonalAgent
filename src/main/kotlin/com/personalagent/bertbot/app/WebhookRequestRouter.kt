package com.personalagent.bertbot.app

import com.personalagent.bertbot.ingestion.connectors.ExternalChatPayloadDispatcher

internal class WebhookRequestRouter(
    private val config: WebhookServerConfig,
    private val security: WebhookSecurityConfig,
    private val rateLimiter: RequestRateLimiter = NoopRequestRateLimiter,
    private val telegramHandler: (String, Boolean) -> String?,
    private val slackHandler: (String, Boolean) -> String?,
    private val whatsAppHandler: (String, Boolean) -> String?,
) {
    constructor(
        config: WebhookServerConfig,
        security: WebhookSecurityConfig,
        dispatcher: ExternalChatPayloadDispatcher,
        rateLimiter: RequestRateLimiter = NoopRequestRateLimiter,
    ) : this(
        config = config,
        security = security,
        rateLimiter = rateLimiter,
        telegramHandler = dispatcher::handleTelegramUpdateJson,
        slackHandler = dispatcher::handleSlackEventJson,
        whatsAppHandler = dispatcher::handleWhatsAppConversationJson,
    )

    fun route(request: WebhookRequest): WebhookResponse {
        if (request.path == config.healthPath) {
            return WebhookResponse(200, "application/json", "{\"status\":\"ok\"}")
        }

        if (request.path == config.whatsAppPath && request.method.equals("GET", ignoreCase = true)) {
            return routeWhatsAppVerification(request)
        }

        val platform = pathToPlatform(request.path) ?: return WebhookResponse(404, "text/plain", "Not Found")

        val clientIp = request.clientIp.orEmpty().ifBlank { "unknown" }
        if (!isWebhookClientIpAllowed(clientIp, security.allowedIpCidrs)) {
            return WebhookResponse(403, "text/plain", "Forbidden")
        }
        if (!rateLimiter.allow(clientIp)) {
            return WebhookResponse(429, "text/plain", "Too Many Requests")
        }

        if (!request.method.equals("POST", ignoreCase = true)) {
            return WebhookResponse(405, "text/plain", "Method Not Allowed")
        }

        val verificationFailure = verifyInboundRequest(platform, request)
        if (verificationFailure != null) {
            return verificationFailure
        }

        val reply =
            when (platform) {
                WebhookPlatform.TELEGRAM -> telegramHandler(request.body, config.dryRun)
                WebhookPlatform.SLACK -> slackHandler(request.body, config.dryRun)
                WebhookPlatform.WHATSAPP -> whatsAppHandler(request.body, config.dryRun)
            }

        return if (reply.isNullOrBlank()) {
            WebhookResponse(202, "application/json", "{\"accepted\":true,\"reply\":null}")
        } else {
            WebhookResponse(200, "application/json", reply)
        }
    }

    private fun routeWhatsAppVerification(request: WebhookRequest): WebhookResponse {
        val mode = request.queryParameters["hub.mode"]
        val verifyToken = request.queryParameters["hub.verify_token"]
        val challenge = request.queryParameters["hub.challenge"]
        if (mode != "subscribe") {
            return WebhookResponse(400, "text/plain", "Invalid mode")
        }

        val expected = security.whatsAppVerifyToken
        if (expected.isNullOrBlank()) {
            return WebhookResponse(503, "text/plain", "WhatsApp verify token is not configured")
        }

        return if (verifyToken == expected && !challenge.isNullOrBlank()) {
            WebhookResponse(200, "text/plain", challenge)
        } else {
            WebhookResponse(403, "text/plain", "Forbidden")
        }
    }

    private fun pathToPlatform(path: String): WebhookPlatform? =
        when (path) {
            config.telegramPath -> WebhookPlatform.TELEGRAM
            config.slackPath -> WebhookPlatform.SLACK
            config.whatsAppPath -> WebhookPlatform.WHATSAPP
            else -> null
        }

    private fun verifyInboundRequest(
        platform: WebhookPlatform,
        request: WebhookRequest,
    ): WebhookResponse? {
        if (!security.requireSignatures) {
            return null
        }

        return when (platform) {
            WebhookPlatform.TELEGRAM -> verifyTelegram(request)
            WebhookPlatform.SLACK -> verifySlack(request)
            WebhookPlatform.WHATSAPP -> verifyWhatsApp(request)
        }
    }

    private fun verifyTelegram(request: WebhookRequest): WebhookResponse? {
        val expectedToken = security.telegramSecretToken
        if (expectedToken.isNullOrBlank()) {
            return WebhookResponse(503, "text/plain", "Telegram secret token is not configured")
        }

        val provided = request.headers["x-telegram-bot-api-secret-token"]
        return if (!webhookConstantTimeEquals(provided.orEmpty(), expectedToken)) {
            WebhookResponse(401, "text/plain", "Unauthorized")
        } else {
            null
        }
    }

    private fun verifySlack(request: WebhookRequest): WebhookResponse? {
        val signingSecret = security.slackSigningSecret
        if (signingSecret.isNullOrBlank()) {
            return WebhookResponse(503, "text/plain", "Slack signing secret is not configured")
        }

        val providedSignature = request.headers["x-slack-signature"] ?: return WebhookResponse(401, "text/plain", "Unauthorized")
        val timestampRaw = request.headers["x-slack-request-timestamp"] ?: return WebhookResponse(401, "text/plain", "Unauthorized")
        val timestamp = timestampRaw.toLongOrNull() ?: return WebhookResponse(401, "text/plain", "Unauthorized")
        val now = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(now - timestamp) > security.slackMaxRequestAgeSeconds) {
            return WebhookResponse(401, "text/plain", "Stale request")
        }

        val base = "v0:$timestamp:${request.body}"
        val expectedSignature = "v0=${webhookHmacSha256Hex(signingSecret, base)}"
        return if (!webhookConstantTimeEquals(providedSignature, expectedSignature)) {
            WebhookResponse(401, "text/plain", "Unauthorized")
        } else {
            null
        }
    }

    private fun verifyWhatsApp(request: WebhookRequest): WebhookResponse? {
        val appSecret = security.whatsAppAppSecret
        if (appSecret.isNullOrBlank()) {
            return WebhookResponse(503, "text/plain", "WhatsApp app secret is not configured")
        }

        val providedSignature = request.headers["x-hub-signature-256"] ?: return WebhookResponse(401, "text/plain", "Unauthorized")
        val expected = "sha256=${webhookHmacSha256Hex(appSecret, request.body)}"
        return if (!webhookConstantTimeEquals(providedSignature, expected)) {
            WebhookResponse(401, "text/plain", "Unauthorized")
        } else {
            null
        }
    }
}

private enum class WebhookPlatform {
    TELEGRAM,
    SLACK,
    WHATSAPP,
}
