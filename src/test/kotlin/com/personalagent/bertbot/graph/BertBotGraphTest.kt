package com.personalagent.bertbot.graph

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.nodes.DelegationNode
import com.personalagent.bertbot.graph.nodes.ExecutorNode
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.nodes.PlannerNode
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphEdge
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(tempFile.exists())

        val reloadedState = stateStore.load()
        assertEquals(state.lastUserMessage, reloadedState.lastUserMessage)
        assertEquals(state.pendingTasks.size, reloadedState.pendingTasks.size)
    }
}
