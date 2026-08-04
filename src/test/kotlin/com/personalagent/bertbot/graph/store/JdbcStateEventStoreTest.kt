package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventType
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class JdbcStateEventStoreTest {
    @Test
    fun `jdbc state event store appends and lists events by scope`() {
        val store = JdbcStateEventStore(jdbcUrl = h2JdbcUrl())

        store.append(
            StateEvent(
                eventId = "e1",
                scopeKey = "scope-a",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "first"),
                createdAtEpochMillis = 1000,
            ),
        )
        store.append(
            StateEvent(
                eventId = "e2",
                scopeKey = "scope-a",
                eventType = StateEventType.CHECKPOINT_CREATED,
                state = BertBotState(lastUserMessage = "second"),
                createdAtEpochMillis = 2000,
            ),
        )

        val listed = store.list("scope-a")

        assertEquals(listOf("e1", "e2"), listed.map { it.eventId })
        assertEquals(listOf("first", "second"), listed.map { it.state.lastUserMessage })
    }

    @Test
    fun `jdbc state event store skips invalid persisted payload rows`() {
        val jdbcUrl = h2JdbcUrl()
        val store = JdbcStateEventStore(jdbcUrl = jdbcUrl)

        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement(
                "INSERT INTO bertbot_state_event (event_id, scope_key, created_at_epoch_millis, payload) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "bad-event")
                statement.setString(2, "scope-a")
                statement.setLong(3, 1000)
                statement.setString(4, "{invalid-json")
                statement.executeUpdate()
            }
        }

        assertEquals(emptyList(), store.list("scope-a"))
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_state_event_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
