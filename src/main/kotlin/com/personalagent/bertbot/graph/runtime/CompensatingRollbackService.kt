package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState
import java.util.UUID

internal interface ToolCompensator {
    fun supports(event: StateEvent): Boolean

    fun buildCompensations(event: StateEvent): List<ToolCompensation>
}

internal interface ToolCompensation {
    val id: String

    val eventId: String

    fun compensate()
}

internal class CompensatingRollbackService(
    private val stateRollbackService: StateOnlyRollbackService,
    private val checkpointStore: BertBotCheckpointStore,
    private val stateEventStore: StateEventStore? = null,
    private val compensators: List<ToolCompensator> = emptyList(),
) : BertBotRollbackService {
    override fun rollbackToCheckpoint(
        scopeKey: String,
        checkpointId: String,
    ): BertBotState {
        val checkpoint =
            requireNotNull(checkpointStore.loadById(scopeKey, checkpointId)) {
                "No checkpoint found for scope '$scopeKey' and checkpointId '$checkpointId'."
            }

        val compensationCount = runCompensations(scopeKey, checkpoint.createdAtEpochMillis)
        val restored = stateRollbackService.rollbackToCheckpoint(scopeKey, checkpointId)
        appendRollbackEvent(scopeKey, checkpoint, restored, compensationCount)
        return restored
    }

    override fun rollbackToLatest(scopeKey: String): BertBotState {
        val latestCheckpoint =
            requireNotNull(checkpointStore.loadLatest(scopeKey)) {
                "No checkpoint found for scope '$scopeKey'."
            }
        return rollbackToCheckpoint(scopeKey, latestCheckpoint.checkpointId)
    }

    private fun runCompensations(
        scopeKey: String,
        checkpointTimestamp: Long,
    ): Int {
        val eventStore = stateEventStore ?: return 0
        if (compensators.isEmpty()) {
            return 0
        }

        var compensationCount = 0
        val eventsToCompensate =
            eventStore
                .list(scopeKey)
                .asSequence()
                .filter { event -> event.createdAtEpochMillis > checkpointTimestamp }
                .sortedByDescending { event -> event.createdAtEpochMillis }
                .toList()

        for (event in eventsToCompensate) {
            for (compensator in compensators) {
                if (!compensator.supports(event)) {
                    continue
                }

                val compensations = compensator.buildCompensations(event)
                for (compensation in compensations) {
                    runCatching {
                        compensation.compensate()
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "Compensation '${compensation.id}' failed for event '${compensation.eventId}'.",
                            error,
                        )
                    }
                    compensationCount += 1
                }
            }
        }

        return compensationCount
    }

    private fun appendRollbackEvent(
        scopeKey: String,
        checkpoint: BertBotCheckpoint,
        restored: BertBotState,
        compensationCount: Int,
    ) {
        val eventStore = stateEventStore ?: return
        eventStore.append(
            StateEvent(
                eventId = UUID.randomUUID().toString(),
                scopeKey = scopeKey,
                traceId = restored.traceId,
                nodeId = checkpoint.nodeId,
                eventType = StateEventType.ROLLBACK_APPLIED,
                state = restored.copyForPersistence(),
                metadata =
                    mapOf(
                        "checkpointId" to checkpoint.checkpointId,
                        "compensationCount" to compensationCount.toString(),
                    ),
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
}

internal class ExternalChatReplyCompensator(
    private val platform: String,
) : ToolCompensator {
    override fun supports(event: StateEvent): Boolean =
        event.metadata["surface"] == "external_chat" && event.metadata["platform"] == platform

    override fun buildCompensations(event: StateEvent): List<ToolCompensation> =
        listOf(
            object : ToolCompensation {
                override val id: String = "external_chat_reply_$platform"
                override val eventId: String = event.eventId

                override fun compensate() {
                    val messageId = event.metadata["messageId"] ?: "unknown"
                    TraceLogger.warn(
                        TracingContext(traceId = event.traceId ?: ""),
                        "external_chat_compensated",
                        "platform=$platform eventId=${event.eventId} messageId=$messageId",
                    )
                }
            },
        )
}
