package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.personalagent.bertbot.ingestion.ApprovalScope
import com.personalagent.bertbot.ingestion.ApprovalUpdateRequest
import com.personalagent.bertbot.ingestion.ExternalChatOutcome
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import java.time.Instant

internal class McpIngestionToolHandler(
    private val ingestionControlPlane: IngestionControlPlane?,
    private val externalChatResponder: ((NormalizedIngestionMessage, Boolean) -> ExternalChatOutcome)?,
) {
    fun setApproval(params: JsonObject): Pair<Boolean, String> {
        val control = ingestionControlPlane ?: return true to "Ingestion control is disabled."
        val arguments = params.argumentsOrSelf()
        val source = mcpIngestionSourceFrom(arguments) ?: return true to "Missing or invalid source parameters."
        val approved = arguments.booleanValue("approved") ?: return true to "Missing required field: approved"
        val scope = arguments.stringValue("scope")?.mcpIngestionApprovalScope() ?: ApprovalScope.CHAT

        val record = control.setApproval(ApprovalUpdateRequest(source = source, scope = scope, approved = approved))
        val status = if (record.approved) "approved" else "revoked"
        return false to "${record.source.platform.name.lowercase()}:${record.source.sourceId} $status at scope ${record.scope.name.lowercase()}."
    }

    fun listApprovedSources(): Pair<Boolean, String> {
        val control = ingestionControlPlane ?: return true to "Ingestion control is disabled."
        val approved = control.listApprovedSources()
        if (approved.isEmpty()) {
            return false to "No approved ingestion sources."
        }

        val lines =
            approved.joinToString(separator = "\n") { record ->
                "${record.source.platform.name.lowercase()} ${record.source.sourceKind.name.lowercase()} ${record.source.sourceId} scope=${record.scope.name.lowercase()}"
            }
        return false to lines
    }

    fun manualIngest(params: JsonObject): Pair<Boolean, String> {
        val control = ingestionControlPlane ?: return true to "Ingestion control is disabled."
        val arguments = params.argumentsOrSelf()
        val source = mcpIngestionSourceFrom(arguments) ?: return true to "Missing or invalid source parameters."
        val text = arguments.stringValue("text")
        val messageId = arguments.stringValue("messageId") ?: "manual-${System.currentTimeMillis()}"
        val dryRun = arguments.booleanValue("dryRun") ?: true
        val senderId = arguments.stringValue("senderId")
        val senderDisplayName = arguments.stringValue("senderDisplayName")
        val threadId = arguments.stringValue("threadId")
        val occurredAt = arguments.stringValue("occurredAt") ?: Instant.now().toString()

        val outcome =
            control.ingestManual(
                messages =
                    listOf(
                        NormalizedIngestionMessage(
                            messageId = messageId,
                            source = source,
                            senderId = senderId,
                            senderDisplayName = senderDisplayName,
                            text = text,
                            threadId = threadId,
                            occurredAt = occurredAt,
                        ),
                    ),
                dryRun = dryRun,
            ).firstOrNull()

        if (outcome == null) {
            return true to "Ingestion returned no outcome."
        }

        return false to "decision=${outcome.decision.name.lowercase()} dryRun=${outcome.dryRun} attachments=${outcome.attachmentRecords.size}"
    }

    fun manualChat(params: JsonObject): Pair<Boolean, String> {
        val responder = externalChatResponder ?: return true to "External chat bridge is disabled."
        val arguments = params.argumentsOrSelf()
        val source = mcpIngestionSourceFrom(arguments) ?: return true to "Missing or invalid source parameters."
        val text = arguments.stringValue("text")
        if (text.isNullOrBlank()) {
            return true to "Missing required field: text"
        }

        val message =
            NormalizedIngestionMessage(
                messageId = arguments.stringValue("messageId") ?: "manual-${System.currentTimeMillis()}",
                source = source,
                senderId = arguments.stringValue("senderId"),
                senderDisplayName = arguments.stringValue("senderDisplayName"),
                text = text,
                threadId = arguments.stringValue("threadId"),
                occurredAt = arguments.stringValue("occurredAt") ?: Instant.now().toString(),
            )

        val dryRun = arguments.booleanValue("dryRun") ?: false
        val outcome = responder(message, dryRun)
        val reply = outcome.outbound?.text ?: ""
        val replySummary = if (reply.isBlank()) "(no reply generated)" else reply
        return false to "decision=${outcome.ingestion.decision.name.lowercase()} dryRun=${outcome.dryRun}\nreply=$replySummary"
    }
}

private fun mcpIngestionSourceFrom(source: JsonObject): IngestionSource? {
    val platformValue = source.stringValue("platform") ?: return null
    val sourceKindValue = source.stringValue("sourceKind") ?: return null
    val sourceIdValue = source.stringValue("sourceId") ?: return null

    val platform = platformValue.mcpIngestionPlatform() ?: return null
    val sourceKind = sourceKindValue.mcpIngestionSourceKind() ?: return null

    return IngestionSource(
        platform = platform,
        sourceKind = sourceKind,
        sourceId = sourceIdValue,
        workspaceId = source.stringValue("workspaceId"),
    )
}

private fun String.mcpIngestionPlatform(): IngestionPlatform? =
    when (lowercase()) {
        "telegram" -> IngestionPlatform.TELEGRAM
        "slack" -> IngestionPlatform.SLACK
        "whatsapp" -> IngestionPlatform.WHATSAPP
        "discord" -> IngestionPlatform.DISCORD
        "manual" -> IngestionPlatform.MANUAL
        else -> null
    }

private fun String.mcpIngestionSourceKind(): IngestionSourceKind? =
    when (lowercase()) {
        "chat" -> IngestionSourceKind.CHAT
        "channel" -> IngestionSourceKind.CHANNEL
        "direct_message" -> IngestionSourceKind.DIRECT_MESSAGE
        "business_conversation" -> IngestionSourceKind.BUSINESS_CONVERSATION
        else -> null
    }

private fun String.mcpIngestionApprovalScope(): ApprovalScope? =
    when (lowercase()) {
        "chat" -> ApprovalScope.CHAT
        "channel" -> ApprovalScope.CHANNEL
        "conversation" -> ApprovalScope.CONVERSATION
        "user" -> ApprovalScope.USER
        else -> null
    }

private fun JsonObject.booleanValue(name: String): Boolean? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        return null
    }
    return runCatching { element.asBoolean }.getOrNull()
}
