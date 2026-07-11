package com.personalagent.bertbot.ingestion

import com.personalagent.bertbot.memory.MemoryEntry
import com.personalagent.bertbot.memory.MemoryStore
import com.personalagent.bertbot.memory.UserProfileStore

interface MediaPolicy {
    fun toAttachmentRecords(attachments: List<NormalizedAttachment>): List<IngestionAttachmentRecord>
}

class ReferenceOnlyMediaPolicy : MediaPolicy {
    override fun toAttachmentRecords(attachments: List<NormalizedAttachment>): List<IngestionAttachmentRecord> =
        attachments.map { attachment ->
            IngestionAttachmentRecord(
                attachmentId = attachment.attachmentId,
                kind = attachment.kind,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                externalUrl = attachment.externalUrl,
                fileReference = attachment.fileReference,
                sizeBytes = attachment.sizeBytes,
                width = attachment.width,
                height = attachment.height,
            )
        }
}

class IngestionService(
    private val consentStore: ConsentStore,
    private val sourceStateStore: SourceStateStore,
    private val episodicMemory: MemoryStore,
    private val semanticSummarizationTrigger: () -> Unit,
    private val userProfileStore: UserProfileStore,
    private val mediaPolicy: MediaPolicy = ReferenceOnlyMediaPolicy(),
) : IngestionControlPlane {
    fun ingest(
        messages: List<NormalizedIngestionMessage>,
        dryRun: Boolean = false,
    ): List<IngestionOutcome> {
        val outcomes = messages.map { message -> ingestMessage(message, dryRun) }
        if (!dryRun && outcomes.any { it.decision == IngestionDecision.APPROVED }) {
            semanticSummarizationTrigger()
        }
        return outcomes
    }

    override fun ingestManual(
        messages: List<NormalizedIngestionMessage>,
        dryRun: Boolean,
    ): List<IngestionOutcome> = ingest(messages, dryRun)

    override fun setApproval(request: ApprovalUpdateRequest): ApprovalRecord {
        val record = ApprovalRecord(source = request.source, scope = request.scope, approved = request.approved)
        consentStore.upsert(record)
        return record
    }

    override fun listApprovedSources(): List<ApprovalRecord> = consentStore.listApproved()

    private fun ingestMessage(
        message: NormalizedIngestionMessage,
        dryRun: Boolean,
    ): IngestionOutcome {
        val cleanedText = message.text?.trim().orEmpty()
        val attachmentRecords = mediaPolicy.toAttachmentRecords(message.attachments)

        if (!consentStore.isApproved(message.source)) {
            return IngestionOutcome(
                message = message,
                decision = IngestionDecision.SKIPPED_UNAPPROVED,
                attachmentRecords = attachmentRecords,
                dryRun = dryRun,
            )
        }

        if (cleanedText.isBlank() && attachmentRecords.isEmpty()) {
            return IngestionOutcome(
                message = message,
                decision = IngestionDecision.SKIPPED_EMPTY,
                attachmentRecords = emptyList(),
                dryRun = dryRun,
            )
        }

        if (!dryRun) {
            episodicMemory.appendMemoryEntry(message.toMemoryEntry(cleanedText, attachmentRecords))
            learnProfileFromApprovedMessage(message, cleanedText)
            sourceStateStore.upsert(
                SyncCursor(
                    source = message.source,
                    cursor = message.messageId,
                    updatedAt = message.occurredAt,
                ),
            )
        }

        return IngestionOutcome(
            message = message,
            decision = IngestionDecision.APPROVED,
            attachmentRecords = attachmentRecords,
            dryRun = dryRun,
        )
    }

    private fun learnProfileFromApprovedMessage(
        message: NormalizedIngestionMessage,
        text: String,
    ) {
        val displayName = message.senderDisplayName?.trim().orEmpty()
        if (displayName.isNotBlank()) {
            userProfileStore.updateDisplayName(displayName)
        }

        parsePreference(text)?.let { preference -> userProfileStore.addRecurringPreference(preference) }
        parseStyleHint(text)?.let { hint -> userProfileStore.addCommunicationStyleHint(hint) }
        parseStableInterest(text)?.let { interest -> userProfileStore.addStableInterest(interest) }
    }
}

private fun MemoryStore.appendMemoryEntry(entry: MemoryEntry) {
    if (this is com.personalagent.bertbot.memory.EpisodicMemory) {
        this.append(entry)
        return
    }
    append(entry.text)
}

private fun NormalizedIngestionMessage.toMemoryEntry(
    cleanedText: String,
    attachmentRecords: List<IngestionAttachmentRecord>,
): MemoryEntry {
    val platformPrefix = source.platform.name.lowercase()
    val sourceText = "$platformPrefix:${source.sourceId}"
    val payload = if (cleanedText.isBlank()) "[attachment-only]" else cleanedText

    return MemoryEntry(
        text = "INGESTED[$sourceText]: $payload",
        createdAt = occurredAt,
        sourceMetadata =
            com.personalagent.bertbot.memory.MemorySourceMetadata(
                platform = source.platform.name,
                sourceKind = source.sourceKind.name,
                sourceId = source.sourceId,
                workspaceId = source.workspaceId,
                senderId = senderId,
                senderDisplayName = senderDisplayName,
                threadId = threadId,
                messageId = messageId,
            ),
        attachmentReferences =
            attachmentRecords.map { record ->
                com.personalagent.bertbot.memory.MemoryAttachmentReference(
                    attachmentId = record.attachmentId,
                    kind = record.kind.name,
                    fileName = record.fileName,
                    mimeType = record.mimeType,
                    externalUrl = record.externalUrl,
                    fileReference = record.fileReference,
                    sizeBytes = record.sizeBytes,
                    width = record.width,
                    height = record.height,
                )
            },
    )
}

private fun parsePreference(text: String): String? {
    val match = Regex("""(?i)\b(i\s+prefer|my\s+preference\s+is)\s+([a-z0-9 ,.'-]{3,80})""").find(text) ?: return null
    return normalizeLearningSnippet(match.groupValues[2])
}

private fun parseStyleHint(text: String): String? {
    if (Regex("""(?i)\bshort\s+answers\b""").containsMatchIn(text)) {
        return "prefers short answers"
    }
    if (Regex("""(?i)\bstep[- ]by[- ]step\b""").containsMatchIn(text)) {
        return "prefers step-by-step explanations"
    }
    return null
}

private fun parseStableInterest(text: String): String? {
    val match = Regex("""(?i)\b(i\s+am\s+interested\s+in|i\s+like)\s+([a-z0-9 ,.'-]{3,80})""").find(text) ?: return null
    return normalizeLearningSnippet(match.groupValues[2])
}

private fun normalizeLearningSnippet(raw: String): String {
    return raw
        .trim()
        .trimEnd('.', '!', '?', ',', ';', ':')
        .replace(Regex("\\s+"), " ")
}
