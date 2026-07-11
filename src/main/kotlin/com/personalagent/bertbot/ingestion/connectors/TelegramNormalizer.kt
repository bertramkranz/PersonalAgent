package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.annotations.SerializedName
import com.personalagent.bertbot.ingestion.AttachmentKind
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedAttachment
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage

data class TelegramChatPayload(
    val id: String,
)

data class TelegramUserPayload(
    val id: String,
    val username: String? = null,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
)

data class TelegramPhotoPayload(
    @SerializedName("file_id")
    val fileId: String,
    val width: Int? = null,
    val height: Int? = null,
)

data class TelegramMessagePayload(
    @SerializedName("message_id")
    val messageId: String,
    @SerializedName("date")
    val dateEpochSeconds: Long,
    val chat: TelegramChatPayload,
    val from: TelegramUserPayload? = null,
    val text: String? = null,
    val caption: String? = null,
    @SerializedName("photo")
    val photos: List<TelegramPhotoPayload>? = null,
)

data class TelegramUpdatePayload(
    @SerializedName("update_id")
    val updateId: String,
    val message: TelegramMessagePayload? = null,
)

object TelegramNormalizer {
    fun normalize(update: TelegramUpdatePayload): NormalizedIngestionMessage? {
        val message = update.message ?: return null
        val sender = message.from
        return NormalizedIngestionMessage(
            messageId = message.messageId,
            source =
                IngestionSource(
                    platform = IngestionPlatform.TELEGRAM,
                    sourceKind = IngestionSourceKind.CHAT,
                    sourceId = message.chat.id,
                ),
            senderId = sender?.id,
            senderDisplayName = telegramDisplayName(sender),
            text = message.text ?: message.caption,
            occurredAt = java.time.Instant.ofEpochSecond(message.dateEpochSeconds).toString(),
            attachments =
                message.photos.orEmpty().map { photo ->
                    NormalizedAttachment(
                        attachmentId = photo.fileId,
                        kind = AttachmentKind.IMAGE,
                        fileReference = photo.fileId,
                        width = photo.width,
                        height = photo.height,
                    )
                },
        )
    }
}

private fun telegramDisplayName(sender: TelegramUserPayload?): String? {
    if (sender == null) {
        return null
    }

    val fullName = listOfNotNull(sender.firstName, sender.lastName).joinToString(" ").trim()
    if (fullName.isNotBlank()) {
        return fullName
    }

    return sender.username?.trim()?.takeIf { it.isNotBlank() }
}
