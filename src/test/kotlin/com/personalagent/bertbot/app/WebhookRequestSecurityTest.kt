package com.personalagent.bertbot.app

import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookRequestSecurityTest {
    @Test
    fun `parse webhook query parameters decodes and skips blank keys`() {
        val parsed = parseWebhookQueryParameters("hub.mode=subscribe&hub.challenge=abc%20123&=ignored")

        assertEquals("subscribe", parsed["hub.mode"])
        assertEquals("abc 123", parsed["hub.challenge"])
        assertFalse(parsed.containsKey(""))
    }

    @Test
    fun `header map flattens first value and lowercases names`() {
        val headers = Headers()
        headers.add("X-Custom-Header", "first")
        headers.add("X-Custom-Header", "second")

        val flattened = headers.toWebhookHeaderMap()

        assertEquals("first", flattened["x-custom-header"])
    }

    @Test
    fun `resolve webhook client ip prefers forwarded headers when trusted`() {
        val headers = mapOf("x-forwarded-for" to "203.0.113.5, 10.0.0.2")

        val ip = resolveWebhookClientIp(headers, remoteAddress = "127.0.0.1", trustProxyHeaders = true)

        assertEquals("203.0.113.5", ip)
    }

    @Test
    fun `resolve webhook client ip uses remote address when proxy headers disabled`() {
        val headers = mapOf("x-forwarded-for" to "203.0.113.5")

        val ip = resolveWebhookClientIp(headers, remoteAddress = "127.0.0.1", trustProxyHeaders = false)

        assertEquals("127.0.0.1", ip)
    }

    @Test
    fun `cidr matcher supports ipv4 rules`() {
        assertTrue(matchesWebhookIpRule("10.20.30.40", "10.0.0.0/8"))
        assertFalse(matchesWebhookIpRule("192.168.1.10", "10.0.0.0/8"))
    }

    @Test
    fun `allowlist matcher accepts exact and cidr rules`() {
        val allowlist = setOf("127.0.0.1", "10.10.0.0/16")

        assertTrue(isWebhookClientIpAllowed("127.0.0.1", allowlist))
        assertTrue(isWebhookClientIpAllowed("10.10.25.4", allowlist))
        assertFalse(isWebhookClientIpAllowed("10.11.25.4", allowlist))
    }

    @Test
    fun `hmac helper is deterministic and compare helper works`() {
        val left = webhookHmacSha256Hex("secret", "payload")
        val right = webhookHmacSha256Hex("secret", "payload")
        val different = webhookHmacSha256Hex("secret", "other")

        assertEquals(left, right)
        assertTrue(webhookConstantTimeEquals(left, right))
        assertFalse(webhookConstantTimeEquals(left, different))
    }
}
