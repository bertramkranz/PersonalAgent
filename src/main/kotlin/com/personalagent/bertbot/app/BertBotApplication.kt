package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore

object BertBotApplication {
    fun createGraph(
        stateStore: BertBotStateStore,
        config: BertBotAgentConfig = BertBotAgentConfig(),
    ): BertBotGraphRunner = BertBotGraphFactory.create(stateStore, config)
}
