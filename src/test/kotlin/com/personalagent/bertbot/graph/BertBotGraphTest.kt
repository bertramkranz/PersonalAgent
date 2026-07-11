package com.personalagent.bertbot.graph

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.nodes.DelegationNode
import com.personalagent.bertbot.graph.nodes.ExecutorNode
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.nodes.PlannerNode
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphEdge
import com.personalagent.bertbot.graph.runtime.BertBotGraphNode
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
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
    fun `graph persists state and routes through planner and executor nodes`() {
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
                    lastUserMessage = "Please review my inbox and delegate urgent items to the right sub-agent.",
                ),
            )

        assertEquals("Please review my inbox and delegate urgent items to the right sub-agent.", state.lastUserMessage)
        assertTrue(state.pendingTasks.isNotEmpty())
        assertTrue(state.delegationPlan.isNotEmpty())
        assertTrue(state.executionSummary.any { it.contains("delegated", ignoreCase = true) })
        assertNotNull(state.traceId)
        assertTrue(state.traceId?.isNotBlank() == true)
        assertTrue(tempFile.exists())

        val reloadedState = stateStore.load()
        assertEquals(state.lastUserMessage, reloadedState.lastUserMessage)
        assertEquals(state.pendingTasks.size, reloadedState.pendingTasks.size)
    }

    @Test
    fun `graph throws max turns exceeded when no node resolves intent`() {
        val tempFile = File.createTempFile("bertbot-state", ".json")
        tempFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(tempFile)
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
        val tempFile = File.createTempFile("bertbot-state", ".json")
        tempFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(tempFile)
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
