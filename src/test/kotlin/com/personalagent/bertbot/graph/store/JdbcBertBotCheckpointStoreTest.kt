package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcBertBotCheckpointStoreTest {
    @Test
    fun `jdbc checkpoint store saves and loads latest per scope`() {
        val jdbcUrl = h2JdbcUrl()
        val store = JdbcBertBotCheckpointStore(jdbcUrl = jdbcUrl)

        store.save(
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "first"),
                createdAtEpochMillis = 1000,
            ),
        )
        store.save(
            BertBotCheckpoint(
                checkpointId = "cp-2",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "executor",
                state = BertBotState(lastUserMessage = "second"),
                createdAtEpochMillis = 2000,
            ),
        )

        val latest = store.loadLatest("scope-a")
        val listed = store.list("scope-a")

        assertNotNull(latest)
        assertEquals("cp-2", latest.checkpointId)
        assertEquals("second", latest.state.lastUserMessage)
        assertEquals(listOf("cp-1", "cp-2"), listed.map { it.checkpointId })
    }

    @Test
    fun `jdbc checkpoint store skips invalid persisted payload row`() {
        val jdbcUrl = h2JdbcUrl()
        val store = JdbcBertBotCheckpointStore(jdbcUrl = jdbcUrl)

        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement(
                "INSERT INTO bertbot_checkpoint_snapshot (scope_key, checkpoint_id, created_at_epoch_millis, payload) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "scope-a")
                statement.setString(2, "cp-bad")
                statement.setLong(3, 1000)
                statement.setString(4, "{invalid-json")
                statement.executeUpdate()
            }
        }

        assertNull(store.loadLatest("scope-a"))
        assertEquals(emptyList(), store.list("scope-a"))
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_checkpoint_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
