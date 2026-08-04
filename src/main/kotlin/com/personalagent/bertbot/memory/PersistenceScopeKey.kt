package com.personalagent.bertbot.memory

import java.security.MessageDigest

internal object PersistenceScopeKey {
    private const val DEFAULT_SCOPE_KEY: String = "global"
    private const val MAX_FILE_SCOPE_KEY_LENGTH: Int = 120
    private const val MAX_JDBC_SCOPE_KEY_LENGTH: Int = 255
    private const val LEGACY_FILE_SCOPE_KEY_LENGTH: Int = 200

    fun normalizeForFile(scopeKey: String): String =
        normalizedWithLimit(scopeKey, MAX_FILE_SCOPE_KEY_LENGTH)

    fun normalizeForJdbc(scopeKey: String): String =
        normalizedWithLimit(scopeKey, MAX_JDBC_SCOPE_KEY_LENGTH)

    fun legacyFileAlias(scopeKey: String): String =
        legacyTruncated(scopeKey, LEGACY_FILE_SCOPE_KEY_LENGTH)

    fun legacyJdbcAlias(scopeKey: String): String =
        legacyTruncated(scopeKey, MAX_JDBC_SCOPE_KEY_LENGTH)

    fun defaultScopeKey(): String = DEFAULT_SCOPE_KEY

    private fun normalizedWithLimit(
        scopeKey: String,
        maxLength: Int,
    ): String {
        val sanitized = scopeKey.trim().ifBlank { DEFAULT_SCOPE_KEY }
        if (sanitized.length <= maxLength) {
            return sanitized
        }

        val suffix = stableHashSuffix(sanitized)
        val prefixBudget = (maxLength - suffix.length - 1).coerceAtLeast(1)
        return "${sanitized.take(prefixBudget)}_$suffix"
    }

    private fun stableHashSuffix(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val shortDigest = bytes.take(8)
        val hex =
            shortDigest.joinToString(separator = "") { byte ->
                "%02x".format(byte)
            }
        return hex
    }

    private fun legacyTruncated(
        scopeKey: String,
        maxLength: Int,
    ): String = scopeKey.trim().ifBlank { DEFAULT_SCOPE_KEY }.take(maxLength)
}
