package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

data class DiscordMessagePayload(
    val messageId: String,
    val channelId: String,
    val guildId: String? = null,
    val authorId: String? = null,
    val authorDisplayName: String? = null,
    val content: String? = null,
    val threadId: String? = null,
    val timestampIso: String? = null,
)

object DiscordNormalizer {
    fun normalize(payload: DiscordMessagePayload): NormalizedIngestionMessage {
        val sourceKind = if (payload.guildId.isNullOrBlank()) IngestionSourceKind.DIRECT_MESSAGE else IngestionSourceKind.CHANNEL
        return NormalizedIngestionMessage(
            messageId = payload.messageId,
            source =
                IngestionSource(
                    platform = IngestionPlatform.DISCORD,
                    sourceKind = sourceKind,
                    sourceId = payload.channelId,
                    workspaceId = payload.guildId,
                ),
            senderId = payload.authorId,
            senderDisplayName = payload.authorDisplayName,
            text = payload.content,
            threadId = payload.threadId,
            occurredAt = payload.timestampIso ?: java.time.Instant.now().toString(),
        )
    }
}
