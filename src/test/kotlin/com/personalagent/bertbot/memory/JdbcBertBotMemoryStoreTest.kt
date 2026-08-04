package com.personalagent.bertbot.memory

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LEGACY_JDBC_SCOPE_LIMIT = 255

class JdbcBertBotMemoryStoreTest {
    @Test
    fun `jdbc memory store persists entries and supports replaceAll`() {
        val jdbcUrl = h2JdbcUrl("memory")
        val store = JdbcBertBotMemoryStore(jdbcUrl = jdbcUrl, tableName = "bertbot_memory_snapshot")

        store.remember("USER: hello")
        store.remember(MemoryEntry(text = "ASSISTANT: hi"))
        assertEquals(2, store.count())

        val reloaded = JdbcBertBotMemoryStore(jdbcUrl = jdbcUrl, tableName = "bertbot_memory_snapshot")
        assertEquals(2, reloaded.count())
        assertTrue(reloaded.entries().any { it.text == "USER: hello" })

        reloaded.replaceAll(listOf(MemoryEntry(text = "Summary: compact")))

        val finalStore = JdbcBertBotMemoryStore(jdbcUrl = jdbcUrl, tableName = "bertbot_memory_snapshot")
        assertEquals(1, finalStore.count())
        assertEquals("Summary: compact", finalStore.entries().single().text)
    }

    @Test
    fun `jdbc memory store isolates entries per scope`() {
        val jdbcUrl = h2JdbcUrl("memory_scope")
        val store = JdbcBertBotMemoryStore(jdbcUrl = jdbcUrl, tableName = "bertbot_memory_scope_snapshot")

        store.withScope("scope-a") {
            store.remember("USER: from-a")
        }
        store.withScope("scope-b") {
            store.remember("USER: from-b")
        }

        val fromA = store.withScope("scope-a") { store.entries().single().text }
        val fromB = store.withScope("scope-b") { store.entries().single().text }

        assertEquals("USER: from-a", fromA)
        assertEquals("USER: from-b", fromB)
    }

    @Test
    fun `jdbc memory store reads legacy truncated scope rows`() {
        val jdbcUrl = h2JdbcUrl("memory_legacy_scope")
        val tableName = "bertbot_memory_scope_legacy"
        val store = JdbcBertBotMemoryStore(jdbcUrl = jdbcUrl, tableName = tableName)

        val longScope = "scope-" + "x".repeat(300)
        val legacyScope = longScope.take(LEGACY_JDBC_SCOPE_LIMIT)
        val payload = "[{\"text\":\"USER: legacy\",\"createdAt\":\"2026-01-01T00:00:00Z\"}]"

        java.sql.DriverManager.getConnection(jdbcUrl).use { connection ->
            val sql = "INSERT INTO $tableName (scope_key, payload) VALUES (?, ?)"
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, legacyScope)
                statement.setString(2, payload)
                statement.executeUpdate()
            }
        }

        val entries = store.withScope(longScope) { store.entries() }
        assertEquals(1, entries.size)
        assertEquals("USER: legacy", entries.single().text)
    }

    private fun h2JdbcUrl(suffix: String): String =
        "jdbc:h2:mem:bertbot_${suffix}_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
