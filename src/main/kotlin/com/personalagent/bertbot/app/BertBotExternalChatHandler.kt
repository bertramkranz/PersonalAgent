package com.personalagent.bertbot.app

import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventStore
import com.personalagent.bertbot.graph.runtime.StateEventType
import com.personalagent.bertbot.graph.runtime.copyForPersistence
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import com.personalagent.bertbot.ingestion.NormalizedOutboundMessage
import java.util.UUID

internal class BertBotExternalChatHandler(
    private val controlPlane: IngestionControlPlane?,
    private val stateStore: BertBotStateStore,
    private val stateEventStore: StateEventStore?,
    private val withPersistenceScope: (String, () -> ExternalChatOutcome) -> ExternalChatOutcome,
    private val respondInScope: (String, String, String) -> String?,
) {
    fun chatFromExternalMessage(
        message: NormalizedIngestionMessage,
        dryRun: Boolean = false,
    ): ExternalChatOutcome {
        if (controlPlane == null) {
            val skipped = IngestionOutcome(message = message, decision = IngestionDecision.SKIPPED_UNAPPROVED, dryRun = dryRun)
            return ExternalChatOutcome(inbound = message, ingestion = skipped, outbound = null, dryRun = dryRun)
        }

        val scopeKey = buildExternalScopeKey(message)
        return withPersistenceScope(scopeKey) {
            val ingestionOutcome = controlPlane.ingestManual(messages = listOf(message), dryRun = dryRun).first()
            if (ingestionOutcome.decision != IngestionDecision.APPROVED || message.text.isNullOrBlank()) {
                return@withPersistenceScope ExternalChatOutcome(inbound = message, ingestion = ingestionOutcome, outbound = null, dryRun = dryRun)
            }

            val response =
                respondInScope(
                    scopeKey,
                    "[external:${message.source.platform.name.lowercase()}:${message.source.sourceId}] ${message.text}",
                    "ext-${message.messageId}",
                ) ?: return@withPersistenceScope ExternalChatOutcome(inbound = message, ingestion = ingestionOutcome, outbound = null, dryRun = dryRun)

            ExternalChatOutcome(
                inbound = message,
                ingestion = ingestionOutcome,
                outbound =
                    NormalizedOutboundMessage(
                        source = message.source,
                        text = response,
                        replyToMessageId = message.messageId,
                        threadId = message.threadId,
                    ),
                dryRun = dryRun,
            ).also {
                emitExternalChatEvent(
                    scopeKey = scopeKey,
                    message = message,
                    response = response,
                    replyToMessageId = message.messageId,
                    threadId = message.threadId,
                )
            }
        }
    }

    private fun buildExternalScopeKey(message: NormalizedIngestionMessage): String {
        val workspace = message.source.workspaceId ?: "none"
        val thread = message.threadId ?: "root"
        return listOf(
            "external",
            message.source.platform.name.lowercase(),
            message.source.sourceKind.name.lowercase(),
            workspace,
            message.source.sourceId,
            thread,
        ).joinToString("|")
    }

    private fun emitExternalChatEvent(
        scopeKey: String,
        message: NormalizedIngestionMessage,
        response: String,
        replyToMessageId: String?,
        threadId: String?,
    ) {
        val eventStore = stateEventStore ?: return
        eventStore.append(
            StateEvent(
                eventId = UUID.randomUUID().toString(),
                scopeKey = scopeKey,
                traceId = message.messageId,
                nodeId = "external_chat",
                eventType = StateEventType.NODE_EXECUTED,
                state = stateStore.load().copyForPersistence(),
                metadata =
                    mapOf(
                        "surface" to "external_chat",
                        "platform" to message.source.platform.name.lowercase(),
                        "messageId" to message.messageId,
                        "replyToMessageId" to (replyToMessageId ?: ""),
                        "threadId" to (threadId ?: ""),
                        "responseLength" to response.length.toString(),
                    ),
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
}
