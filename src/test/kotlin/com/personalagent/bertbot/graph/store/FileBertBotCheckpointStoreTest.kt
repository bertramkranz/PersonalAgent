package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileBertBotCheckpointStoreTest {
    @Test
    fun `save and load latest checkpoint by scope`() {
        val file = File.createTempFile("bertbot-checkpoints", ".json")
        file.delete()
        file.deleteOnExit()
        val store = FileBertBotCheckpointStore(file)

        store.save(
            BertBotCheckpoint(
                checkpointId = "c1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "first"),
                createdAtEpochMillis = 1000,
            ),
        )
        store.save(
            BertBotCheckpoint(
                checkpointId = "c2",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "executor",
                state = BertBotState(lastUserMessage = "second"),
                createdAtEpochMillis = 2000,
            ),
        )
        store.save(
            BertBotCheckpoint(
                checkpointId = "c3",
                scopeKey = "scope-b",
                traceId = "trace-b",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "third"),
                createdAtEpochMillis = 3000,
            ),
        )

        val latestA = store.loadLatest("scope-a")
        val latestB = store.loadLatest("scope-b")

        assertNotNull(latestA)
        assertEquals("c2", latestA.checkpointId)
        assertEquals("second", latestA.state.lastUserMessage)

        assertNotNull(latestB)
        assertEquals("c3", latestB.checkpointId)
        assertEquals("third", latestB.state.lastUserMessage)
    }

    @Test
    fun `load by id and list are scope-aware`() {
        val file = File.createTempFile("bertbot-checkpoints", ".json")
        file.delete()
        file.deleteOnExit()
        val store = FileBertBotCheckpointStore(file)

        store.save(
            BertBotCheckpoint(
                checkpointId = "c1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "capture",
                state = BertBotState(lastUserMessage = "a1"),
                createdAtEpochMillis = 1000,
            ),
        )
        store.save(
            BertBotCheckpoint(
                checkpointId = "c2",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "a2"),
                createdAtEpochMillis = 2000,
            ),
        )

        val found = store.loadById("scope-a", "c1")
        val notFound = store.loadById("scope-b", "c1")
        val scopeA = store.list("scope-a")
        val scopeB = store.list("scope-b")

        assertNotNull(found)
        assertEquals("a1", found.state.lastUserMessage)
        assertNull(notFound)
        assertEquals(listOf("c1", "c2"), scopeA.map { it.checkpointId })
        assertEquals(emptyList(), scopeB)
    }
}
