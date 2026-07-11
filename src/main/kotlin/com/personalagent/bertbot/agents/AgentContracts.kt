package com.personalagent.bertbot.agents

import com.personalagent.bertbot.graph.runtime.TracingContext

interface Agent<I, O> {
    fun execute(
        input: I,
        tracingContext: TracingContext,
    ): O
}

interface Skill<I, O> {
    fun invoke(
        input: I,
        tracingContext: TracingContext,
    ): O
}
