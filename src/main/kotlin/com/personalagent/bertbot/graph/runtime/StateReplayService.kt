package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

internal class StateReplayService(
    private val checkpointStore: BertBotCheckpointStore,
    private val eventStore: StateEventStore,
) {
    fun replayEventsToCheckpoint(
        scopeKey: String,
        targetCheckpointId: String,
    ): BertBotState {
        val checkpoint =
            requireNotNull(checkpointStore.loadById(scopeKey, targetCheckpointId)) {
                "No checkpoint found for scope '$scopeKey' and checkpointId '$targetCheckpointId'."
            }

        val replayed =
            eventStore
                .list(scopeKey)
                .asSequence()
                .filter { it.createdAtEpochMillis <= checkpoint.createdAtEpochMillis }
                .maxByOrNull { it.createdAtEpochMillis }
                ?.state
                ?.copyForPersistence()

        return replayed ?: checkpoint.state.copyForPersistence()
    }
}
