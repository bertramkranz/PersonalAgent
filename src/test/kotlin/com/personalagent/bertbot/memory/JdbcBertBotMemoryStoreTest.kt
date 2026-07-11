package com.personalagent.bertbot.memory

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun h2JdbcUrl(suffix: String): String =
        "jdbc:h2:mem:bertbot_${suffix}_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
