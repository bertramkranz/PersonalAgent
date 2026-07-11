package com.personalagent.bertbot.ingestion

import java.time.Instant
import java.time.format.DateTimeFormatter

enum class IngestionPlatform {
    TELEGRAM,
    SLACK,
    WHATSAPP,
    MANUAL,
}

enum class IngestionSourceKind {
    CHAT,
    CHANNEL,
    DIRECT_MESSAGE,
    BUSINESS_CONVERSATION,
}

enum class ApprovalScope {
    CHAT,
    CHANNEL,
    CONVERSATION,
    USER,
}

enum class AttachmentKind {
    IMAGE,
    FILE,
    UNKNOWN,
}

data class IngestionSource(
    val platform: IngestionPlatform,
    val sourceKind: IngestionSourceKind,
    val sourceId: String,
    val workspaceId: String? = null,
)

data class NormalizedAttachment(
    val attachmentId: String,
    val kind: AttachmentKind = AttachmentKind.UNKNOWN,
    val fileName: String? = null,
    val mimeType: String? = null,
    val externalUrl: String? = null,
    val fileReference: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class NormalizedIngestionMessage(
    val messageId: String,
    val source: IngestionSource,
    val senderId: String? = null,
    val senderDisplayName: String? = null,
    val text: String? = null,
    val threadId: String? = null,
    val occurredAt: String = Instant.now().atZone(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
    val attachments: List<NormalizedAttachment> = emptyList(),
)

data class ApprovalRecord(
    val source: IngestionSource,
    val scope: ApprovalScope,
    val approved: Boolean,
    val updatedAt: String = Instant.now().atZone(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
)

data class SyncCursor(
    val source: IngestionSource,
    val cursor: String,
    val updatedAt: String = Instant.now().atZone(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
)

enum class IngestionDecision {
    APPROVED,
    SKIPPED_UNAPPROVED,
    SKIPPED_EMPTY,
}

data class IngestionAttachmentRecord(
    val attachmentId: String,
    val kind: AttachmentKind,
    val fileName: String? = null,
    val mimeType: String? = null,
    val externalUrl: String? = null,
    val fileReference: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class IngestionOutcome(
    val message: NormalizedIngestionMessage,
    val decision: IngestionDecision,
    val attachmentRecords: List<IngestionAttachmentRecord> = emptyList(),
    val dryRun: Boolean = false,
)

data class NormalizedOutboundMessage(
    val source: IngestionSource,
    val text: String,
    val replyToMessageId: String? = null,
    val threadId: String? = null,
)

data class ExternalChatOutcome(
    val inbound: NormalizedIngestionMessage,
    val ingestion: IngestionOutcome,
    val outbound: NormalizedOutboundMessage? = null,
    val dryRun: Boolean = false,
)

data class ApprovalUpdateRequest(
    val source: IngestionSource,
    val scope: ApprovalScope = ApprovalScope.CHAT,
    val approved: Boolean,
)

interface IngestionControlPlane {
    fun setApproval(request: ApprovalUpdateRequest): ApprovalRecord

    fun listApprovedSources(): List<ApprovalRecord>

    fun ingestManual(
        messages: List<NormalizedIngestionMessage>,
        dryRun: Boolean = true,
    ): List<IngestionOutcome>
}
