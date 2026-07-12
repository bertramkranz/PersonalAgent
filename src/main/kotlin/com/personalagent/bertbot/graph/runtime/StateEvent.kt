package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

enum class StateEventType {
    NODE_EXECUTED,
    CHECKPOINT_CREATED,
    ROLLBACK_APPLIED,
}

data class StateEvent(
    val eventId: String,
    val scopeKey: String,
    val traceId: String? = null,
    val nodeId: String? = null,
    val eventType: StateEventType,
    val state: BertBotState,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtEpochMillis: Long,
)

interface StateEventStore {
    fun append(event: StateEvent)

    fun list(scopeKey: String): List<StateEvent>
}
