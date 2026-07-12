package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventType
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

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_state_event_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
