package com.personalagent.bertbot.app

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionHistoryStoreTest {
    @Test
    fun `file session history store trims per scope and keeps scopes isolated`() {
        val file = File.createTempFile("bertbot-session-history", ".jsonl")
        file.delete()
        file.deleteOnExit()

        val store = FileSessionHistoryStore(file = file, maxEntriesPerScope = 3)

        store.append(entry("global-1", SessionHistoryRole.USER))

        store.withScope("scope-a") {
            store.append(entry("a-1", SessionHistoryRole.USER))
            store.append(entry("a-2", SessionHistoryRole.ASSISTANT))
            store.append(entry("a-3", SessionHistoryRole.USER))
            store.append(entry("a-4", SessionHistoryRole.ASSISTANT))
        }

        store.withScope("scope-b") {
            store.append(entry("b-1", SessionHistoryRole.USER))
        }

        assertEquals(listOf("global-1"), store.list(10).map { it.text })

        val scopeA =
            store.withScope("scope-a") {
                store.list(10)
            }
        assertEquals(listOf("a-2", "a-3", "a-4"), scopeA.map { it.text })

        val scopeB =
            store.withScope("scope-b") {
                store.list(10)
            }
        assertEquals(listOf("b-1"), scopeB.map { it.text })
    }

    @Test
    fun `jdbc session history store trims and clears only current scope`() {
        val jdbcUrl = h2JdbcUrl()
        val store =
            JdbcSessionHistoryStore(
                jdbcUrl = jdbcUrl,
                tableName = "bertbot_session_history_event",
                maxEntriesPerScope = 2,
            )

        store.withScope("scope-a") {
            store.append(entry("a-1", SessionHistoryRole.USER))
            store.append(entry("a-2", SessionHistoryRole.ASSISTANT))
            store.append(entry("a-3", SessionHistoryRole.USER))
        }

        store.withScope("scope-b") {
            store.append(entry("b-1", SessionHistoryRole.USER))
        }

        val scopeA = store.withScope("scope-a") { store.list(10) }
        val scopeB = store.withScope("scope-b") { store.list(10) }

        assertEquals(listOf("a-2", "a-3"), scopeA.map { it.text })
        assertEquals(listOf("b-1"), scopeB.map { it.text })

        store.withScope("scope-a") { store.clear() }

        assertTrue(store.withScope("scope-a") { store.list(10).isEmpty() })
        assertEquals(listOf("b-1"), store.withScope("scope-b") { store.list(10).map { it.text } })
    }

    @Test
    fun `file session history store search returns scoped matches`() {
        val file = File.createTempFile("bertbot-session-history-search", ".jsonl")
        file.delete()
        file.deleteOnExit()

        val store = FileSessionHistoryStore(file = file, maxEntriesPerScope = 20)
        store.append(entry("global hello", SessionHistoryRole.USER))
        store.withScope("scope-a") {
            store.append(entry("scope-alpha", SessionHistoryRole.USER))
            store.append(entry("scope-beta", SessionHistoryRole.ASSISTANT))
        }

        val globalMatches = store.search(query = "hello", limit = 10)
        assertEquals(listOf("global hello"), globalMatches.map { it.text })

        val scopedMatches = store.withScope("scope-a") { store.search(query = "scope", limit = 10) }
        assertEquals(listOf("scope-alpha", "scope-beta"), scopedMatches.map { it.text })
    }

    @Test
    fun `jdbc session history store search returns scoped matches`() {
        val store =
            JdbcSessionHistoryStore(
                jdbcUrl = h2JdbcUrl(),
                tableName = "bertbot_session_history_event",
                maxEntriesPerScope = 20,
            )

        store.append(entry("global alpha", SessionHistoryRole.USER))
        store.withScope("scope-a") {
            store.append(entry("scope alpha", SessionHistoryRole.USER))
            store.append(entry("scope beta", SessionHistoryRole.ASSISTANT))
        }

        val globalMatches = store.search(query = "alpha", limit = 10)
        assertEquals(listOf("global alpha"), globalMatches.map { it.text })

        val scopedMatches = store.withScope("scope-a") { store.search(query = "scope", limit = 10) }
        assertEquals(listOf("scope alpha", "scope beta"), scopedMatches.map { it.text })
    }

    private fun entry(
        text: String,
        role: SessionHistoryRole,
    ): SessionHistoryEntry =
        SessionHistoryEntry(
            timestamp = "2026-08-04T00:00:00Z",
            role = role,
            text = text,
            traceId = "trace-$text",
            source = "chat",
        )

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_session_history_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
