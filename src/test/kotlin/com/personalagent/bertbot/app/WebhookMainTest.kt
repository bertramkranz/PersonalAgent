package com.personalagent.bertbot.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookMainTest {
    @Test
    fun `resolve webhook server config uses defaults`() {
        val config = resolveWebhookServerConfig(environment = emptyMap(), dotEnvValues = emptyMap())

        assertEquals("0.0.0.0", config.host)
        assertEquals(8088, config.port)
        assertEquals("/webhook/telegram", config.telegramPath)
        assertEquals("/webhook/slack", config.slackPath)
        assertEquals("/webhook/whatsapp", config.whatsAppPath)
        assertEquals("/health", config.healthPath)
        assertFalse(config.dryRun)
    }

    @Test
    fun `resolve webhook server config honors env overrides`() {
        val config =
            resolveWebhookServerConfig(
                environment =
                    mapOf(
                        "BERTBOT_WEBHOOK_HOST" to "127.0.0.1",
                        "BERTBOT_WEBHOOK_PORT" to "8099",
                        "BERTBOT_WEBHOOK_TELEGRAM_PATH" to "tg",
                        "BERTBOT_WEBHOOK_SLACK_PATH" to "/sl",
                        "BERTBOT_WEBHOOK_WHATSAPP_PATH" to "wa",
                        "BERTBOT_WEBHOOK_HEALTH_PATH" to "status",
                        "BERTBOT_WEBHOOK_DRY_RUN" to "true",
                    ),
            )

        assertEquals("127.0.0.1", config.host)
        assertEquals(8099, config.port)
        assertEquals("/tg", config.telegramPath)
        assertEquals("/sl", config.slackPath)
        assertEquals("/wa", config.whatsAppPath)
        assertEquals("/status", config.healthPath)
        assertTrue(config.dryRun)
    }

    @Test
    fun `resolve webhook agent config enables all connectors by default`() {
        val config = resolveWebhookAgentConfig(environment = emptyMap(), dotEnvValues = emptyMap())

        assertTrue(config.ingestion.policy.enabled)
        assertTrue(config.ingestion.telegram.connector.enabled)
        assertTrue(config.ingestion.slack.connector.enabled)
        assertTrue(config.ingestion.whatsapp.connector.enabled)
        assertFalse(config.ingestion.discord.connector.enabled)
        assertTrue(config.ingestion.policy.requireApproval)
    }

    @Test
    fun `resolve webhook security config honors env overrides`() {
        val config =
            resolveWebhookSecurityConfig(
                environment =
                    mapOf(
                        "BERTBOT_WEBHOOK_REQUIRE_SIGNATURES" to "true",
                        "BERTBOT_WEBHOOK_TRUST_PROXY_HEADERS" to "true",
                        "BERTBOT_WEBHOOK_ALLOWED_IPS" to "127.0.0.1,10.0.0.0/8",
                        "BERTBOT_TELEGRAM_SECRET_TOKEN" to "tg-secret",
                        "BERTBOT_SLACK_SIGNING_SECRET" to "slack-secret",
                        "BERTBOT_SLACK_MAX_REQUEST_AGE_SECONDS" to "600",
                        "BERTBOT_WEBHOOK_RATE_LIMIT_WINDOW_SECONDS" to "120",
                        "BERTBOT_WEBHOOK_RATE_LIMIT_MAX_REQUESTS" to "250",
                        "BERTBOT_WHATSAPP_APP_SECRET" to "wa-secret",
                        "BERTBOT_WHATSAPP_VERIFY_TOKEN" to "wa-verify",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertTrue(config.requireSignatures)
        assertTrue(config.trustProxyHeaders)
        assertEquals(setOf("127.0.0.1", "10.0.0.0/8"), config.allowedIpCidrs)
        assertEquals("tg-secret", config.telegramSecretToken)
        assertEquals("slack-secret", config.slackSigningSecret)
        assertEquals(600, config.slackMaxRequestAgeSeconds)
        assertEquals(120, config.rateLimitWindowSeconds)
        assertEquals(250, config.rateLimitMaxRequests)
        assertEquals("wa-secret", config.whatsAppAppSecret)
        assertEquals("wa-verify", config.whatsAppVerifyToken)
    }

    @Test
    fun `resolve webhook agent config honors discord connector metadata`() {
        val config =
            resolveWebhookAgentConfig(
                environment =
                    mapOf(
                        "BERTBOT_DISCORD_ENABLED" to "true",
                        "BERTBOT_DISCORD_GUILD_ID" to "guild-1",
                        "BERTBOT_DISCORD_APPROVED_CHANNEL_IDS" to "ch-1,ch-2",
                        "BERTBOT_DISCORD_APPROVED_DIRECT_MESSAGE_IDS" to "dm-7",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertTrue(config.ingestion.discord.connector.enabled)
        assertEquals("guild-1", config.ingestion.discord.guildId)
        assertEquals(setOf("ch-1", "ch-2"), config.ingestion.discord.approvedChannelIds)
        assertEquals(setOf("dm-7"), config.ingestion.discord.approvedDirectMessageIds)
    }

    @Test
    fun `resolve discord bot runtime config reads token and enabled flag`() {
        val config =
            resolveDiscordBotRuntimeConfig(
                environment =
                    mapOf(
                        "BERTBOT_DISCORD_ENABLED" to "true",
                        "BERTBOT_DISCORD_BOT_TOKEN" to "test-token",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertTrue(config.enabled)
        assertEquals("test-token", config.token)
    }

    @Test
    fun `router routes platform endpoints and returns status codes`() {
        val config = WebhookServerConfig(dryRun = true)
        val router =
            WebhookRequestRouter(
                config = config,
                security = WebhookSecurityConfig(),
                telegramHandler = { _, _ -> "{\"platform\":\"telegram\"}" },
                slackHandler = { _, _ -> "{\"platform\":\"slack\"}" },
                whatsAppHandler = { _, _ -> "{\"platform\":\"whatsapp\"}" },
            )

        val health = router.route(request("GET", "/health"))
        assertEquals(200, health.statusCode)

        val telegram = router.route(request("POST", "/webhook/telegram", body = "{}"))
        assertEquals(200, telegram.statusCode)
        assertTrue(telegram.body.contains("telegram"))

        val slack = router.route(request("POST", "/webhook/slack", body = "{}"))
        assertEquals(200, slack.statusCode)
        assertTrue(slack.body.contains("slack"))

        val whatsapp = router.route(request("POST", "/webhook/whatsapp", body = "{}"))
        assertEquals(200, whatsapp.statusCode)
        assertTrue(whatsapp.body.contains("whatsapp"))

        val methodNotAllowed = router.route(request("GET", "/webhook/telegram"))
        assertEquals(405, methodNotAllowed.statusCode)

        val notFound = router.route(request("POST", "/webhook/unknown", body = "{}"))
        assertEquals(404, notFound.statusCode)
    }

    @Test
    fun `router returns accepted when no reply generated`() {
        val router =
            WebhookRequestRouter(
                config = WebhookServerConfig(),
                security = WebhookSecurityConfig(),
                telegramHandler = { _, _ -> null },
                slackHandler = { _, _ -> null },
                whatsAppHandler = { _, _ -> null },
            )

        val response = router.route(request("POST", "/webhook/telegram", body = "{}"))

        assertEquals(202, response.statusCode)
        assertTrue(response.body.contains("\"accepted\":true"))
    }

    @Test
    fun `router blocks non-allowlisted client ips`() {
        val router =
            WebhookRequestRouter(
                config = WebhookServerConfig(),
                security = WebhookSecurityConfig(allowedIpCidrs = setOf("10.10.0.0/16")),
                telegramHandler = { _, _ -> "ok" },
                slackHandler = { _, _ -> "ok" },
                whatsAppHandler = { _, _ -> "ok" },
            )

        val denied =
            router.route(
                request(
                    method = "POST",
                    path = "/webhook/telegram",
                    clientIp = "192.168.1.7",
                    body = "{}",
                ),
            )
        assertEquals(403, denied.statusCode)

        val allowed =
            router.route(
                request(
                    method = "POST",
                    path = "/webhook/telegram",
                    clientIp = "10.10.1.8",
                    body = "{}",
                ),
            )
        assertEquals(200, allowed.statusCode)
    }

    @Test
    fun `router enforces request rate limit per client`() {
        val router =
            WebhookRequestRouter(
                config = WebhookServerConfig(),
                security = WebhookSecurityConfig(),
                rateLimiter =
                    object : RequestRateLimiter {
                        private var calls: Int = 0

                        override fun allow(clientKey: String): Boolean {
                            calls += 1
                            return calls <= 1
                        }
                    },
                telegramHandler = { _, _ -> "ok" },
                slackHandler = { _, _ -> "ok" },
                whatsAppHandler = { _, _ -> "ok" },
            )

        val first = router.route(request("POST", "/webhook/telegram", clientIp = "127.0.0.1", body = "{}"))
        assertEquals(200, first.statusCode)

        val second = router.route(request("POST", "/webhook/telegram", clientIp = "127.0.0.1", body = "{}"))
        assertEquals(429, second.statusCode)
    }

    @Test
    fun `router enforces telegram secret token when signature checks enabled`() {
        val router =
            WebhookRequestRouter(
                config = WebhookServerConfig(),
                security = WebhookSecurityConfig(requireSignatures = true, telegramSecretToken = "expected-token"),
                telegramHandler = { _, _ -> "ok" },
                slackHandler = { _, _ -> "ok" },
                whatsAppHandler = { _, _ -> "ok" },
            )

        val unauthorized = router.route(request("POST", "/webhook/telegram", body = "{}"))
        assertEquals(401, unauthorized.statusCode)

        val authorized =
            router.route(
                request(method = "POST", path = "/webhook/telegram", body = "{}")
                    .copy(headers = mapOf("x-telegram-bot-api-secret-token" to "expected-token")),
            )
        assertEquals(200, authorized.statusCode)
    }

    @Test
    fun `router enforces slack signature when signature checks enabled`() {
        val secret = "slack-secret"
        val body = "{\"text\":\"hello\"}"
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = "v0=${computeSlackSignature(secret, timestamp, body)}"
        val router =
            WebhookRequestRouter(
                config = WebhookServerConfig(),
                security = WebhookSecurityConfig(requireSignatures = true, slackSigningSecret = secret, slackMaxRequestAgeSeconds = 300),
                telegramHandler = { _, _ -> "ok" },
                slackHandler = { _, _ -> "ok" },
                whatsAppHandler = { _, _ -> "ok" },
            )

        val unauthorized = router.route(request("POST", "/webhook/slack", body = body))
        assertEquals(401, unauthorized.statusCode)

        val authorized =
            router.route(
                request(method = "POST", path = "/webhook/slack", body = body)
                    .copy(
                        headers =
                            mapOf(
                                "x-slack-signature" to signature,
                                "x-slack-request-timestamp" to timestamp,
                            ),
                    ),
            )
        assertEquals(200, authorized.statusCode)
    }

    @Test
    fun `router supports whatsapp verification challenge`() {
        val router =
            WebhookRequestRouter(
                config = WebhookServerConfig(),
                security = WebhookSecurityConfig(requireSignatures = true, whatsAppVerifyToken = "verify-token"),
                telegramHandler = { _, _ -> "ok" },
                slackHandler = { _, _ -> "ok" },
                whatsAppHandler = { _, _ -> "ok" },
            )

        val response =
            router.route(
                request(method = "GET", path = "/webhook/whatsapp")
                    .copy(
                        queryParameters =
                            mapOf(
                                "hub.mode" to "subscribe",
                                "hub.verify_token" to "verify-token",
                                "hub.challenge" to "12345",
                            ),
                    ),
            )

        assertEquals(200, response.statusCode)
        assertEquals("12345", response.body)
    }
}

private fun request(
    method: String,
    path: String,
    body: String = "",
    clientIp: String? = null,
): WebhookRequest =
    WebhookRequest(
        method = method,
        path = path,
        queryParameters = emptyMap(),
        headers = emptyMap(),
        clientIp = clientIp,
        body = body,
    )

private fun computeSlackSignature(
    secret: String,
    timestamp: String,
    body: String,
): String {
    val base = "v0:$timestamp:$body"
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(base.toByteArray(Charsets.UTF_8)).joinToString(separator = "") { byte -> "%02x".format(byte) }
}
