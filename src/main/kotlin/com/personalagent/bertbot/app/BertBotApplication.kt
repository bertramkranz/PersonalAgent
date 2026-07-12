package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore

object BertBotApplication {
    fun createGraph(
        stateStore: BertBotStateStore,
        config: BertBotAgentConfig = BertBotAgentConfig(),
        checkpointStore: BertBotCheckpointStore? = null,
        enableAutomaticCheckpointing: Boolean = false,
        eventSourcingConfiguration: BertBotGraphRunner.EventSourcingConfiguration = BertBotGraphRunner.EventSourcingConfiguration(),
    ): BertBotGraphRunner =
        BertBotGraphFactory.create(
            stateStore = stateStore,
            config = config,
            checkpointStore = checkpointStore,
            enableAutomaticCheckpointing = enableAutomaticCheckpointing,
            eventSourcingConfiguration = eventSourcingConfiguration,
        )
}
