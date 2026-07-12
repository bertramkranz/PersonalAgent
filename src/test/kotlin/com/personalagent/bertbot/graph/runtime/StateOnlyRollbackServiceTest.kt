package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.store.FileBertBotCheckpointStore
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StateOnlyRollbackServiceTest {
    @Test
    fun `rollback to checkpoint restores state into scoped store`() {
        val stateFile = File.createTempFile("bertbot-state", ".json")
        stateFile.delete()
        stateFile.deleteOnExit()
        val checkpointFile = File.createTempFile("bertbot-checkpoints", ".json")
        checkpointFile.delete()
        checkpointFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(stateFile)
        val checkpointStore = FileBertBotCheckpointStore(checkpointFile)
        val service = StateOnlyRollbackService(stateStore, checkpointStore)

        checkpointStore.save(
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "restore me", pendingTasks = mutableListOf("task")),
                createdAtEpochMillis = 1000,
            ),
        )

        val restored = service.rollbackToCheckpoint("scope-a", "cp-1")
        val loaded = stateStore.withScope("scope-a") { stateStore.load() }

        assertEquals("restore me", restored.lastUserMessage)
        assertEquals("restore me", loaded.lastUserMessage)
        assertEquals(listOf("task"), loaded.pendingTasks)
    }

    @Test
    fun `rollback to latest fails when no checkpoint exists`() {
        val stateFile = File.createTempFile("bertbot-state", ".json")
        stateFile.delete()
        stateFile.deleteOnExit()
        val checkpointFile = File.createTempFile("bertbot-checkpoints", ".json")
        checkpointFile.delete()
        checkpointFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(stateFile)
        val checkpointStore = FileBertBotCheckpointStore(checkpointFile)
        val service = StateOnlyRollbackService(stateStore, checkpointStore)

        assertFailsWith<IllegalArgumentException> {
            service.rollbackToLatest("scope-missing")
        }
    }
}
