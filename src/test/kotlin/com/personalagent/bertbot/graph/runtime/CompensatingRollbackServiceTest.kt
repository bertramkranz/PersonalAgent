package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.store.FileBertBotCheckpointStore
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import com.personalagent.bertbot.graph.store.FileStateEventStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompensatingRollbackServiceTest {
    @Test
    fun `rollback runs compensations for events newer than target checkpoint`() {
        val stateFile = File.createTempFile("bertbot-state", ".json")
        val checkpointFile = File.createTempFile("bertbot-checkpoints", ".json")
        val eventFile = File.createTempFile("bertbot-events", ".json")
        stateFile.delete()
        checkpointFile.delete()
        eventFile.delete()
        stateFile.deleteOnExit()
        checkpointFile.deleteOnExit()
        eventFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(stateFile)
        val checkpointStore = FileBertBotCheckpointStore(checkpointFile)
        val eventStore = FileStateEventStore(eventFile)

        checkpointStore.save(
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "executor",
                state = BertBotState(lastUserMessage = "restore me"),
                createdAtEpochMillis = 1000,
            ),
        )

        eventStore.append(
            StateEvent(
                eventId = "old-event",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "executor",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "old"),
                createdAtEpochMillis = 900,
            ),
        )
        eventStore.append(
            StateEvent(
                eventId = "new-event",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "executor",
                eventType = StateEventType.NODE_EXECUTED,
                state = BertBotState(lastUserMessage = "new"),
                metadata = mapOf("tool" to "calendar.create"),
                createdAtEpochMillis = 1100,
            ),
        )

        val compensated = mutableListOf<String>()
        val service =
            CompensatingRollbackService(
                stateRollbackService = StateOnlyRollbackService(stateStore, checkpointStore),
                checkpointStore = checkpointStore,
                stateEventStore = eventStore,
                compensators =
                    listOf(
                        object : ToolCompensator {
                            override fun supports(event: StateEvent): Boolean = event.metadata["tool"] != null

                            override fun buildCompensations(event: StateEvent): List<ToolCompensation> {
                                return listOf(
                                    object : ToolCompensation {
                                        override val id: String = "undo-tool"
                                        override val eventId: String = event.eventId

                                        override fun compensate() {
                                            compensated.add(event.eventId)
                                        }
                                    },
                                )
                            }
                        },
                    ),
            )

        val restored = service.rollbackToCheckpoint("scope-a", "cp-1")

        assertEquals("restore me", restored.lastUserMessage)
        assertEquals(listOf("new-event"), compensated)
        val events = eventStore.list("scope-a")
        assertTrue(events.any { it.eventType == StateEventType.ROLLBACK_APPLIED })
    }
}
