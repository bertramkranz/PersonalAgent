package com.personalagent.bertbot.graph

import com.personalagent.bertbot.graph.model.BertBotPriority
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.nodes.DelegationNode
import com.personalagent.bertbot.graph.nodes.ExecutorNode
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.nodes.PlannerNode
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphEdge
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.graph.runtime.InvalidStateHandoffException
import com.personalagent.bertbot.graph.runtime.MaxTurnsExceededException
import com.personalagent.bertbot.graph.runtime.RequiredFieldsStateValidator
import com.personalagent.bertbot.graph.runtime.StateHandoffValidator
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BertBotGraphTest {
    @Test
    fun `graph writes automatic checkpoints using provided scope key`() {
        val stateStore = InMemoryBertBotStateStore()
        val recordingCheckpointStore = RecordingCheckpointStore()
        val definition =
            BertBotGraphDefinition(
                entryNodeId = NodeIds.CAPTURE,
                nodes = listOf(MessageCaptureNode()),
                edges = emptyList(),
            )
        val graph =
            BertBotGraphRunner(
                definition = definition,
                stateStore = stateStore,
                checkpointStore = recordingCheckpointStore,
                enableAutomaticCheckpointing = true,
            )

        graph.run(
            initialState = BertBotState(lastUserMessage = "checkpoint scope"),
            checkpointScopeKey = "external_telegram_user_1",
        )

        assertEquals(1, recordingCheckpointStore.saved.size)
        assertEquals("external_telegram_user_1", recordingCheckpointStore.saved.first().scopeKey)
    }

    @Test
    fun `graph persists latest execution snapshot and routes through planner and executor nodes`() {
        val tempFile = File.createTempFile("bertbot-state", ".json")
        tempFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(tempFile)
        val definition =
            BertBotGraphDefinition(
                entryNodeId = NodeIds.CAPTURE,
                nodes =
                    listOf(
                        MessageCaptureNode(),
                        PlannerNode(),
                        DelegationNode(),
                        ExecutorNode(),
                    ),
                edges =
                    listOf(
                        BertBotGraphEdge(NodeIds.CAPTURE, NodeIds.PLANNER) { true },
                        BertBotGraphEdge(NodeIds.PLANNER, NodeIds.DELEGATION) { it.pendingTasks.isNotEmpty() },
                        BertBotGraphEdge(NodeIds.PLANNER, NodeIds.EXECUTOR) { it.pendingTasks.isEmpty() },
                        BertBotGraphEdge(NodeIds.DELEGATION, NodeIds.EXECUTOR) { true },
                    ),
            )
        val graph = BertBotGraphRunner(definition, stateStore)

        val state =
            graph.run(
                BertBotState(
                    lastUserMessage = "Please review the system architecture and dependency structure.",
                ),
            )

        assertEquals("Please review the system architecture and dependency structure.", state.lastUserMessage)
        assertTrue(state.pendingTasks.isNotEmpty())
        assertTrue(state.delegationPlan.isNotEmpty())
        assertTrue(state.executionSummary.any { it.contains("delegated", ignoreCase = true) })
        assertEquals(BertBotPriority.ROUTINE, state.currentIntent?.priority)
        assertTrue(state.intentResolved)
        assertNotNull(state.traceId)
        assertTrue(state.traceId?.isNotBlank() == true)
        assertTrue(tempFile.exists())

        val reloadedState = stateStore.load()
        assertEquals(state.lastUserMessage, reloadedState.lastUserMessage)
        assertEquals(state.pendingTasks.size, reloadedState.pendingTasks.size)
        assertEquals(state.currentIntent, reloadedState.currentIntent)
        val storedContent = tempFile.readText()
        assertTrue(storedContent.contains("\"schemaVersion\":2"))
        assertTrue(storedContent.contains("\"lastUserMessage\":"))
        assertTrue(!storedContent.contains("\"state\":"))
    }

    @Test
    fun `graph starts each request from the provided state instead of persisted workflow fields`() {
        val stateStore = InMemoryBertBotStateStore()
        stateStore.save(
            BertBotState(
                traceId = "persisted-trace",
                lastUserMessage = "stale message",
                pendingTasks = mutableListOf("stale task"),
                delegationPlan = mutableListOf("stale delegation"),
                memorySummary = mutableListOf("stale memory"),
                profileSummary = mutableListOf("stale profile"),
                executionSummary = mutableListOf("stale execution"),
                selectedSubAgent = "architect",
                intentResolved = true,
            ),
        )

        val definition =
            BertBotGraphDefinition(
                entryNodeId = NodeIds.CAPTURE,
                nodes = listOf(MessageCaptureNode()),
                edges = emptyList(),
            )
        val graph = BertBotGraphRunner(definition, stateStore)

        val state = graph.run(BertBotState(lastUserMessage = "fresh request"))

        assertEquals("fresh request", state.lastUserMessage)
        assertTrue(state.pendingTasks.isEmpty())
        assertTrue(state.delegationPlan.isEmpty())
        assertTrue(state.profileSummary.isEmpty())
        assertTrue(state.executionSummary.any { it.contains("Captured input", ignoreCase = true) })
        assertTrue(state.executionSummary.none { it.contains("stale", ignoreCase = true) })
        assertTrue(state.traceId?.startsWith("persisted") != true)
    }

    @Test
    fun `state store loads legacy unversioned state payloads`() {
        val tempFile = File.createTempFile("bertbot-state", ".json")
        tempFile.deleteOnExit()
        tempFile.writeText(
            """
            {"lastUserMessage":"legacy request","pendingTasks":["legacy task"]}
            """.trimIndent(),
        )

        val state = FileBertBotStateStore(tempFile).load()

        assertEquals("legacy request", state.lastUserMessage)
        assertEquals(listOf("legacy task"), state.pendingTasks)
    }

    @Test
    fun `state store loads legacy versioned wrapper payloads`() {
        val tempFile = File.createTempFile("bertbot-state", ".json")
        tempFile.deleteOnExit()
        tempFile.writeText(
            """
            {
              "schemaVersion": 1,
              "state": {
                "lastUserMessage": "legacy wrapped request",
                "pendingTasks": ["legacy wrapped task"],
                "intentResolved": true
              }
            }
            """.trimIndent(),
        )

        val state = FileBertBotStateStore(tempFile).load()

        assertEquals("legacy wrapped request", state.lastUserMessage)
        assertEquals(listOf("legacy wrapped task"), state.pendingTasks)
        assertTrue(state.intentResolved)
    }

    @Test
    fun `graph skips delegation execution when no sub-agent match is found`() {
        val stateStore = InMemoryBertBotStateStore()
        val definition =
            BertBotGraphDefinition(
                entryNodeId = NodeIds.CAPTURE,
                nodes =
                    listOf(
                        MessageCaptureNode(),
                        PlannerNode(),
                        DelegationNode(),
                        ExecutorNode(),
                    ),
                edges =
                    listOf(
                        BertBotGraphEdge(NodeIds.CAPTURE, NodeIds.PLANNER) { true },
                        BertBotGraphEdge(NodeIds.PLANNER, NodeIds.DELEGATION) { it.pendingTasks.isNotEmpty() },
                        BertBotGraphEdge(NodeIds.PLANNER, NodeIds.EXECUTOR) { it.pendingTasks.isEmpty() },
                        BertBotGraphEdge(NodeIds.DELEGATION, NodeIds.EXECUTOR) { true },
                    ),
            )
        val graph = BertBotGraphRunner(definition, stateStore)

        val state =
            graph.run(
                BertBotState(
                    lastUserMessage = "Please handle banana shipment details",
                ),
            )

        assertTrue(state.delegationPlan.isEmpty())
        assertEquals(null, state.selectedSubAgent)
        assertTrue(state.executionSummary.any { it.contains("Skipped delegation", ignoreCase = true) })
        assertTrue(!state.intentResolved)
    }

    @Test
    fun `graph can route planner directly to executor when no follow-up is needed`() {
        val stateStore = InMemoryBertBotStateStore()
        val definition =
            BertBotGraphDefinition(
                entryNodeId = NodeIds.CAPTURE,
                nodes =
                    listOf(
                        MessageCaptureNode(),
                        PlannerNode(),
                        DelegationNode(),
                        ExecutorNode(),
                    ),
                edges =
                    listOf(
                        BertBotGraphEdge(NodeIds.CAPTURE, NodeIds.PLANNER) { true },
                        BertBotGraphEdge(NodeIds.PLANNER, NodeIds.DELEGATION) { it.pendingTasks.isNotEmpty() },
                        BertBotGraphEdge(NodeIds.PLANNER, NodeIds.EXECUTOR) { it.pendingTasks.isEmpty() },
                        BertBotGraphEdge(NodeIds.DELEGATION, NodeIds.EXECUTOR) { true },
                    ),
            )
        val graph = BertBotGraphRunner(definition, stateStore)

        val state = graph.run(BertBotState(lastUserMessage = "hello"))

        assertTrue(state.pendingTasks.isEmpty())
        assertTrue(state.executionSummary.any { it.contains("No delegated execution required", ignoreCase = true) })
        assertTrue(state.intentResolved)
    }

    @Test
    fun `graph throws max turns exceeded when no node resolves intent`() {
        val stateStore = InMemoryBertBotStateStore()
        val spinningNode =
            object : BertBotGraphNode {
                override val id: String = "SPIN"

                override fun execute(
                    state: BertBotState,
                    tracingContext: TracingContext,
                ): BertBotState {
                    state.executionSummary.add("Still spinning")
                    return state
                }
            }

        val definition =
            BertBotGraphDefinition(
                entryNodeId = "SPIN",
                nodes = listOf(spinningNode),
                edges = listOf(BertBotGraphEdge("SPIN", "SPIN") { true }),
            )
        val graph = BertBotGraphRunner(definition = definition, stateStore = stateStore, maxTurns = 2)

        assertFailsWith<MaxTurnsExceededException> {
            graph.run(BertBotState(lastUserMessage = "loop forever"))
        }
    }

    @Test
    fun `graph blocks invalid state handoff when required fields are missing`() {
        val stateStore = InMemoryBertBotStateStore()
        val firstNode =
            object : BertBotGraphNode {
                override val id: String = "FIRST"

                override fun execute(
                    state: BertBotState,
                    tracingContext: TracingContext,
                ): BertBotState = state
            }
        val secondNode =
            object : BertBotGraphNode {
                override val id: String = "SECOND"

                override fun execute(
                    state: BertBotState,
                    tracingContext: TracingContext,
                ): BertBotState {
                    state.executionSummary.add("Should not execute")
                    return state
                }
            }

        val definition =
            BertBotGraphDefinition(
                entryNodeId = "FIRST",
                nodes = listOf(firstNode, secondNode),
                edges = listOf(BertBotGraphEdge("FIRST", "SECOND") { true }),
            )
        val validator =
            StateHandoffValidator(
                fromNodeId = "FIRST",
                toNodeId = "SECOND",
                validator =
                    RequiredFieldsStateValidator<BertBotState>(
                        requiredFields = mapOf("pendingTasks" to { state -> state.pendingTasks }),
                    ),
            )
        val graph =
            BertBotGraphRunner(
                definition = definition,
                stateStore = stateStore,
                handoffValidators = listOf(validator),
            )

        assertFailsWith<InvalidStateHandoffException> {
            graph.run(BertBotState(lastUserMessage = "trigger invalid handoff"))
        }
    }

    @Test
    fun `state store preserves unreadable json before resetting state`() {
        val tempDirectory = createTempDirectory(prefix = "bertbot-state-store").toFile()
        tempDirectory.deleteOnExit()
        val stateFile = File(tempDirectory, "bertbot-state.json")
        stateFile.writeText("{not-valid-json")

        val state = FileBertBotStateStore(stateFile).load()

        assertEquals("", state.lastUserMessage)
        val backups = tempDirectory.listFiles { _, name -> name.startsWith("bertbot-state.corrupt-") }
        assertTrue(backups?.isNotEmpty() == true)
    }
}

private class RecordingCheckpointStore : BertBotCheckpointStore {
    val saved: MutableList<BertBotCheckpoint> = mutableListOf()

    override fun save(checkpoint: BertBotCheckpoint) {
        saved.add(checkpoint)
    }

    override fun loadLatest(scopeKey: String): BertBotCheckpoint? =
        saved
            .asReversed()
            .firstOrNull { it.scopeKey == scopeKey }

    override fun loadById(
        scopeKey: String,
        checkpointId: String,
    ): BertBotCheckpoint? =
        saved.firstOrNull { it.scopeKey == scopeKey && it.checkpointId == checkpointId }

    override fun list(scopeKey: String): List<BertBotCheckpoint> =
        saved.filter { it.scopeKey == scopeKey }
}

private class InMemoryBertBotStateStore : BertBotStateStore {
    private var state: BertBotState = BertBotState()

    override fun load(): BertBotState = state.copy()

    override fun save(state: BertBotState) {
        this.state = state.copy()
    }
}
