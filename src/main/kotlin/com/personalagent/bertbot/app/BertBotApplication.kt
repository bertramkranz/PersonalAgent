package com.personalagent.bertbot.app

import com.personalagent.bertbot.agents.SubAgentRegistry
import com.personalagent.bertbot.config.KoogAgentConfig
import com.personalagent.bertbot.graph.nodes.DelegationNode
import com.personalagent.bertbot.graph.nodes.ExecutorNode
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.nodes.PlannerNode
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphEdge
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.graph.runtime.DelegationToExecutorStateValidator
import com.personalagent.bertbot.graph.runtime.StateHandoffValidator

object BertBotApplication {
    fun createGraph(
        stateStore: BertBotStateStore,
        config: KoogAgentConfig = KoogAgentConfig(),
    ): BertBotGraphRunner {
        val registry = SubAgentRegistry(config)

        return BertBotGraphRunner(
            definition =
                BertBotGraphDefinition(
                    entryNodeId = NodeIds.CAPTURE,
                    nodes =
                        listOf(
                            MessageCaptureNode(),
                            PlannerNode(),
                            DelegationNode(registry),
                            ExecutorNode(),
                        ),
                    edges =
                        listOf(
                            BertBotGraphEdge(NodeIds.CAPTURE, NodeIds.PLANNER) { true },
                            BertBotGraphEdge(NodeIds.PLANNER, NodeIds.DELEGATION) { it.pendingTasks.isNotEmpty() },
                            BertBotGraphEdge(NodeIds.PLANNER, NodeIds.EXECUTOR) { it.pendingTasks.isEmpty() },
                            BertBotGraphEdge(NodeIds.DELEGATION, NodeIds.EXECUTOR) { true },
                        ),
                ),
            stateStore = stateStore,
            handoffValidators =
                listOf(
                    StateHandoffValidator(
                        fromNodeId = NodeIds.DELEGATION,
                        toNodeId = NodeIds.EXECUTOR,
                        validator = DelegationToExecutorStateValidator(),
                    ),
                ),
        )
    }
}
