package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore

object BertBotApplication {
    internal fun createGraph(
        stateStore: BertBotStateStore,
        config: BertBotAgentConfig = BertBotAgentConfig(),
        options: BertBotGraphFactory.GraphCreationOptions = BertBotGraphFactory.GraphCreationOptions(),
    ): BertBotGraphRunner =
        BertBotGraphFactory.create(
            stateStore = stateStore,
            config = config,
            options = options,
        )
}
