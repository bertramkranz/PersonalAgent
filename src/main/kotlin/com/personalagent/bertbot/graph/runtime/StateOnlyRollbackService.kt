package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

internal class StateOnlyRollbackService(
    private val stateStore: BertBotStateStore,
    private val checkpointStore: BertBotCheckpointStore,
) : BertBotRollbackService {
    override fun rollbackToCheckpoint(
        scopeKey: String,
        checkpointId: String,
    ): BertBotState {
        val checkpoint =
            requireNotNull(checkpointStore.loadById(scopeKey, checkpointId)) {
                "No checkpoint found for scope '$scopeKey' and checkpointId '$checkpointId'."
            }
        val restored = checkpoint.state.copyForPersistence()
        stateStore.withScope(scopeKey) {
            stateStore.save(restored)
        }
        return restored
    }

    override fun rollbackToLatest(scopeKey: String): BertBotState {
        val checkpoint =
            requireNotNull(checkpointStore.loadLatest(scopeKey)) {
                "No checkpoint found for scope '$scopeKey'."
            }
        val restored = checkpoint.state.copyForPersistence()
        stateStore.withScope(scopeKey) {
            stateStore.save(restored)
        }
        return restored
    }
}

internal fun BertBotState.copyForPersistence(): BertBotState =
    BertBotState(
        traceId = traceId,
        lastUserMessage = lastUserMessage,
        pendingTasks = pendingTasks.toMutableList(),
        delegationPlan = delegationPlan.toMutableList(),
        memorySummary = memorySummary.toMutableList(),
        profileSummary = profileSummary.toMutableList(),
        executionSummary = executionSummary.toMutableList(),
        currentIntent = currentIntent,
        delegationDecision = delegationDecision,
        selectedSubAgent = selectedSubAgent,
        intentResolved = intentResolved,
    )
