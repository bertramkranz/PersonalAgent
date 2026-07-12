package com.personalagent.bertbot.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

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

fun main() {
    val webhookConfig = resolveWebhookServerConfig()
    val securityConfig = resolveWebhookSecurityConfig()
    val agentConfig = resolveWebhookAgentConfig()
    val workspaceRoot = resolveWorkspaceRoot()
    val runtime =
        BertBotRuntimeFactory.create(
            config = agentConfig,
            workspaceRoot = workspaceRoot,
            enablePeriodicResearchScheduler = true,
        )
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
        val queryParameters = parseWebhookQueryParameters(exchange.requestURI?.rawQuery)
        val headers = exchange.requestHeaders.toWebhookHeaderMap()
        val clientIp = resolveWebhookClientIp(headers, exchange.remoteAddress?.address?.hostAddress, trustProxyHeaders)
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

internal fun createRequestRateLimiter(config: WebhookSecurityConfig): RequestRateLimiter =
    SlidingWindowRequestRateLimiter(
        windowSeconds = config.rateLimitWindowSeconds,
        maxRequests = config.rateLimitMaxRequests,
    )
