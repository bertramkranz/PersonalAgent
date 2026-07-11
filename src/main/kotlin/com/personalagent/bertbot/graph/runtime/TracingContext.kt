package com.personalagent.bertbot.graph.runtime

import java.util.UUID

data class TracingContext(
    val traceId: String = UUID.randomUUID().toString(),
)
