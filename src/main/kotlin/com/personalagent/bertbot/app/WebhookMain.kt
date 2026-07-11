package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.ConnectorConfig
import com.personalagent.bertbot.config.IngestionConfig
import com.personalagent.bertbot.config.IngestionPolicyConfig
import com.personalagent.bertbot.config.SlackIntegrationConfig
import com.personalagent.bertbot.config.TelegramIntegrationConfig
import com.personalagent.bertbot.config.WhatsAppIntegrationConfig
import com.personalagent.bertbot.ingestion.connectors.ExternalChatPayloadDispatcher
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class WebhookServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8088,
    val telegramPath: String = "/webhook/telegram",
    val slackPath: String = "/webhook/slack",
    val whatsAppPath: String = "/webhook/whatsapp",
    val healthPath: String = "/health",
    val dryRun: Boolean = false,
)

internal data class WebhookSecurityConfig(
    val requireSignatures: Boolean = false,
    val trustProxyHeaders: Boolean = false,
    val allowedIpCidrs: Set<String> = emptySet(),
    val telegramSecretToken: String? = null,
    val slackSigningSecret: String? = null,
    val slackMaxRequestAgeSeconds: Long = 300,
    val whatsAppAppSecret: String? = null,
    val whatsAppVerifyToken: String? = null,
    val rateLimitWindowSeconds: Long = 60,
    val rateLimitMaxRequests: Int = 120,
)

internal data class WebhookRequest(
    val method: String,
    val path: String,
    val queryParameters: Map<String, String>,
    val headers: Map<String, String>,
    val clientIp: String? = null,
    val body: String,
)

internal interface RequestRateLimiter {
    fun allow(clientKey: String): Boolean
}

internal object NoopRequestRateLimiter : RequestRateLimiter {
    override fun allow(clientKey: String): Boolean = true
}

internal class SlidingWindowRequestRateLimiter(
    private val windowSeconds: Long,
    private val maxRequests: Int,
) : RequestRateLimiter {
    private val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()

    override fun allow(clientKey: String): Boolean {
        if (windowSeconds <= 0 || maxRequests <= 0) {
            return true
        }

        val now = System.currentTimeMillis() / 1000
        val cutoff = now - windowSeconds
        val queue = buckets.computeIfAbsent(clientKey) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.first() <= cutoff) {
                queue.removeFirst()
            }
            if (queue.size >= maxRequests) {
                return false
            }
            queue.addLast(now)
            return true
        }
    }
}

internal data class WebhookResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String,
)

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
        if (!isClientIpAllowed(clientIp, security.allowedIpCidrs)) {
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
        return if (!constantTimeEquals(provided.orEmpty(), expectedToken)) {
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
        val expectedSignature = "v0=${hmacSha256Hex(signingSecret, base)}"
        return if (!constantTimeEquals(providedSignature, expectedSignature)) {
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
        val expected = "sha256=${hmacSha256Hex(appSecret, request.body)}"
        return if (!constantTimeEquals(providedSignature, expected)) {
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

fun main() {
    val webhookConfig = resolveWebhookServerConfig()
    val securityConfig = resolveWebhookSecurityConfig()
    val agentConfig = resolveWebhookAgentConfig()
    val runtime = BertBotRuntimeFactory.create(config = agentConfig)
    if (runtime == null) {
        printMissingApiKeyHelp()
        return
    }

    val dispatcher = runtime.externalPayloadDispatcher()
    val rateLimiter = createRequestRateLimiter(securityConfig)
    val router = WebhookRequestRouter(webhookConfig, securityConfig, dispatcher, rateLimiter)

    val server = HttpServer.create(InetSocketAddress(webhookConfig.host, webhookConfig.port), 0)
    server.createContext("/") { exchange ->
        handleHttpExchange(exchange, router, securityConfig.trustProxyHeaders)
    }
    server.executor = null

    println("BertBot webhook server started on http://${webhookConfig.host}:${webhookConfig.port}")
    println("Telegram endpoint: ${webhookConfig.telegramPath}")
    println("Slack endpoint: ${webhookConfig.slackPath}")
    println("WhatsApp endpoint: ${webhookConfig.whatsAppPath}")
    println("Health endpoint: ${webhookConfig.healthPath}")
    println("Signature verification required: ${securityConfig.requireSignatures}")
    println("Proxy header trust enabled: ${securityConfig.trustProxyHeaders}")
    println("IP allowlist entries: ${securityConfig.allowedIpCidrs.size}")
    println("Rate limit: ${securityConfig.rateLimitMaxRequests} requests/${securityConfig.rateLimitWindowSeconds}s")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { server.stop(1) }
            runCatching { runtime.close() }
        },
    )

    server.start()
}

private fun handleHttpExchange(
    exchange: HttpExchange,
    router: WebhookRequestRouter,
    trustProxyHeaders: Boolean,
) {
    exchange.use {
        val method = exchange.requestMethod
        val path = exchange.requestURI?.path.orEmpty()
        val queryParameters = parseQueryParameters(exchange.requestURI?.rawQuery)
        val headers = exchange.requestHeaders.toFlatHeaderMap()
        val clientIp = resolveClientIp(headers, exchange.remoteAddress?.address?.hostAddress, trustProxyHeaders)
        val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val response =
            router.route(
                WebhookRequest(
                    method = method,
                    path = path,
                    queryParameters = queryParameters,
                    headers = headers,
                    clientIp = clientIp,
                    body = body,
                ),
            )
        writeResponse(exchange, response)
    }
}

private fun writeResponse(
    exchange: HttpExchange,
    response: WebhookResponse,
) {
    val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "${response.contentType}; charset=utf-8")
    exchange.sendResponseHeaders(response.statusCode, bytes.size.toLong())
    exchange.responseBody.use { stream ->
        stream.write(bytes)
    }
}

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

internal fun createRequestRateLimiter(config: WebhookSecurityConfig): RequestRateLimiter =
    SlidingWindowRequestRateLimiter(
        windowSeconds = config.rateLimitWindowSeconds,
        maxRequests = config.rateLimitMaxRequests,
    )

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
    return if (raw.startsWith("/")) raw else "/$raw"
}

private fun String?.toBooleanEnv(defaultValue: Boolean): Boolean {
    val value = this?.trim()?.lowercase() ?: return defaultValue
    return when (value) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}

private fun hmacSha256Hex(
    secret: String,
    payload: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac
        .doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun constantTimeEquals(
    left: String,
    right: String,
): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) {
        return emptyMap()
    }

    return rawQuery
        .split("&")
        .mapNotNull { pair ->
            val key = pair.substringBefore("=", missingDelimiterValue = "").trim()
            if (key.isBlank()) {
                return@mapNotNull null
            }
            val value = pair.substringAfter("=", missingDelimiterValue = "")
            decodeQueryComponent(key) to decodeQueryComponent(value)
        }.toMap()
}

private fun decodeQueryComponent(value: String): String =
    runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

private fun com.sun.net.httpserver.Headers.toFlatHeaderMap(): Map<String, String> =
    entries.associate { (name, values) ->
        name.lowercase() to values.firstOrNull().orEmpty()
    }

private fun parseCsvSet(raw: String?): Set<String> =
    raw
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()

private fun resolveClientIp(
    headers: Map<String, String>,
    remoteAddress: String?,
    trustProxyHeaders: Boolean,
): String {
    if (trustProxyHeaders) {
        headers["x-forwarded-for"]
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        headers["x-real-ip"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    return remoteAddress?.trim().takeUnless { it.isNullOrBlank() } ?: "unknown"
}

private fun isClientIpAllowed(
    clientIp: String,
    allowlist: Set<String>,
): Boolean {
    if (allowlist.isEmpty()) {
        return true
    }

    return allowlist.any { rule ->
        matchesIpRule(clientIp, rule)
    }
}

private fun matchesIpRule(
    clientIp: String,
    rule: String,
): Boolean {
    if (!rule.contains("/")) {
        return clientIp == rule
    }

    val network = rule.substringBefore('/').trim()
    val prefixLength = rule.substringAfter('/').trim().toIntOrNull() ?: return false
    val clientAddress = runCatching { InetAddress.getByName(clientIp).address }.getOrNull() ?: return false
    val networkAddress = runCatching { InetAddress.getByName(network).address }.getOrNull() ?: return false

    if (clientAddress.size != networkAddress.size) {
        return false
    }

    val totalBits = clientAddress.size * 8
    if (prefixLength !in 0..totalBits) {
        return false
    }

    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8

    for (index in 0 until fullBytes) {
        if (clientAddress[index] != networkAddress[index]) {
            return false
        }
    }

    if (remainingBits == 0) {
        return true
    }

    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    val clientPart = clientAddress[fullBytes].toInt() and mask
    val networkPart = networkAddress[fullBytes].toInt() and mask
    return clientPart == networkPart
}
