package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState
import kotlin.test.Test
import kotlin.test.assertEquals

class StateReplayServiceTest {
    @Test
    fun `replay returns latest event state at or before target checkpoint timestamp`() {
        val checkpointStore = InMemoryCheckpointStore()
        val eventStore = InMemoryStateEventStore()
        val replayService = StateReplayService(checkpointStore, eventStore)

        checkpointStore.save(
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "checkpoint"),
                createdAtEpochMillis = 2000,
            ),
        )

        eventStore.append(
            StateEvent(
                eventId = "e1",
                scopeKey = "scope-a",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "before checkpoint"),
                createdAtEpochMillis = 1500,
            ),
        )
        eventStore.append(
            StateEvent(
                eventId = "e2",
                scopeKey = "scope-a",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "after checkpoint"),
                createdAtEpochMillis = 2500,
            ),
        )

        val replayed = replayService.replayEventsToCheckpoint("scope-a", "cp-1")

        assertEquals("before checkpoint", replayed.lastUserMessage)
    }
}

private class InMemoryStateEventStore : StateEventStore {
    private val events = mutableListOf<StateEvent>()

    override fun append(event: StateEvent) {
        events.add(event)
    }

    override fun list(scopeKey: String): List<StateEvent> =
        events
            .filter { it.scopeKey == scopeKey }
            .sortedBy { it.createdAtEpochMillis }
}

private class InMemoryCheckpointStore : BertBotCheckpointStore {
    private val checkpoints = mutableListOf<BertBotCheckpoint>()

    override fun save(checkpoint: BertBotCheckpoint) {
        checkpoints.removeIf { it.scopeKey == checkpoint.scopeKey && it.checkpointId == checkpoint.checkpointId }
        checkpoints.add(checkpoint)
    }

    override fun loadLatest(scopeKey: String): BertBotCheckpoint? =
        checkpoints
            .filter { it.scopeKey == scopeKey }
            .maxByOrNull { it.createdAtEpochMillis }

    override fun loadById(
        scopeKey: String,
        checkpointId: String,
    ): BertBotCheckpoint? =
        checkpoints.firstOrNull { it.scopeKey == scopeKey && it.checkpointId == checkpointId }

    override fun list(scopeKey: String): List<BertBotCheckpoint> =
        checkpoints
            .filter { it.scopeKey == scopeKey }
            .sortedBy { it.createdAtEpochMillis }
}
