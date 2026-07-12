package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FileStateEventStoreTest {
    @Test
    fun `append and list are scope-aware and ordered`() {
        val file = File.createTempFile("bertbot-events", ".json")
        file.delete()
        file.deleteOnExit()
        val store = FileStateEventStore(file)

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
        store.append(
            StateEvent(
                eventId = "e3",
                scopeKey = "scope-b",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "third"),
                createdAtEpochMillis = 3000,
            ),
        )

        val scopeA = store.list("scope-a")
        val scopeB = store.list("scope-b")

        assertEquals(listOf("e1", "e2"), scopeA.map { it.eventId })
        assertEquals(listOf("first", "second"), scopeA.map { it.state.lastUserMessage })
        assertEquals(listOf("e3"), scopeB.map { it.eventId })
    }
}
