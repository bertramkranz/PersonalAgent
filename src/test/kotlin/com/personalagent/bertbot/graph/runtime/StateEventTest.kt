package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState
import kotlin.test.Test
import kotlin.test.assertEquals

class StateEventTest {
    @Test
    fun `state event keeps provided identifiers and metadata`() {
        val event =
            StateEvent(
                eventId = "evt-1",
                scopeKey = "scope-a",
                traceId = "trace-1",
                nodeId = "node-1",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "hello"),
                metadata = mapOf("source" to "test"),
                createdAtEpochMillis = 1234L,
            )

        assertEquals("evt-1", event.eventId)
        assertEquals("scope-a", event.scopeKey)
        assertEquals(StateEventType.NODE_EXECUTED, event.eventType)
        assertEquals("hello", event.state.lastUserMessage)
        assertEquals("test", event.metadata["source"])
        assertEquals(1234L, event.createdAtEpochMillis)
    }
}
