package com.personalagent.bertbot.app

import com.sun.net.httpserver.Headers
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun webhookHmacSha256Hex(
    secret: String,
    payload: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac
        .doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun webhookConstantTimeEquals(
    left: String,
    right: String,
): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

internal fun parseWebhookQueryParameters(rawQuery: String?): Map<String, String> {
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
            decodeWebhookQueryComponent(key) to decodeWebhookQueryComponent(value)
        }.toMap()
}

internal fun Headers.toWebhookHeaderMap(): Map<String, String> =
    entries.associate { (name, values) ->
        name.lowercase() to values.firstOrNull().orEmpty()
    }

internal fun resolveWebhookClientIp(
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

internal fun isWebhookClientIpAllowed(
    clientIp: String,
    allowlist: Set<String>,
): Boolean {
    if (allowlist.isEmpty()) {
        return true
    }

    return allowlist.any { rule ->
        matchesWebhookIpRule(clientIp, rule)
    }
}

internal fun matchesWebhookIpRule(
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

private fun decodeWebhookQueryComponent(value: String): String =
    runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)
