package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.annotations.SerializedName
import com.personalagent.bertbot.ingestion.AttachmentKind
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedAttachment
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

data class SlackFilePayload(
    val id: String,
    val name: String? = null,
    val mimetype: String? = null,
    @SerializedName("url_private")
    val urlPrivate: String? = null,
    val size: Long? = null,
)

data class SlackMessageEventPayload(
    val ts: String,
    val channel: String,
    val user: String? = null,
    val text: String? = null,
    @SerializedName("thread_ts")
    val threadTs: String? = null,
    val files: List<SlackFilePayload>? = null,
)

data class SlackEnvelopePayload(
    @SerializedName("team_id")
    val teamId: String,
    val event: SlackMessageEventPayload,
)

object SlackNormalizer {
    fun normalize(payload: SlackEnvelopePayload): NormalizedIngestionMessage {
        val event = payload.event
        return NormalizedIngestionMessage(
            messageId = event.ts,
            source =
                IngestionSource(
                    platform = IngestionPlatform.SLACK,
                    sourceKind = IngestionSourceKind.CHANNEL,
                    sourceId = event.channel,
                    workspaceId = payload.teamId,
                ),
            senderId = event.user,
            text = event.text,
            threadId = event.threadTs,
            occurredAt = slackTimestampToInstant(event.ts),
            attachments =
                event.files.orEmpty().map { file ->
                    NormalizedAttachment(
                        attachmentId = file.id,
                        kind = classifySlackAttachment(file.mimetype),
                        fileName = file.name,
                        mimeType = file.mimetype,
                        externalUrl = file.urlPrivate,
                        sizeBytes = file.size,
                    )
                },
        )
    }
}

private fun slackTimestampToInstant(rawTs: String): String {
    val seconds = rawTs.substringBefore('.').toLongOrNull() ?: return java.time.Instant.now().toString()
    val nanos = rawTs.substringAfter('.', "0").take(9).padEnd(9, '0').toIntOrNull() ?: 0
    return java.time.Instant.ofEpochSecond(seconds, nanos.toLong()).toString()
}

private fun classifySlackAttachment(mimeType: String?): AttachmentKind {
    return when {
        mimeType.isNullOrBlank() -> AttachmentKind.UNKNOWN
        mimeType.startsWith("image/") -> AttachmentKind.IMAGE
        else -> AttachmentKind.FILE
    }
}
