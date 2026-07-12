package com.personalagent.bertbot.ingestion.connectors

import com.google.gson.annotations.SerializedName
import com.personalagent.bertbot.ingestion.AttachmentKind
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSource
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import com.personalagent.bertbot.ingestion.NormalizedAttachment
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WhatsAppImagePayload(
    val id: String,
    @SerialName("mime_type")
    @SerializedName("mime_type")
    val mimeType: String? = null,
)

@Serializable
data class WhatsAppMessagePayload(
    val id: String,
    val from: String,
    @SerialName("timestamp")
    @SerializedName("timestamp")
    val timestampSeconds: Long,
    @SerialName("text_body")
    @SerializedName("text_body")
    val textBody: String? = null,
    val image: WhatsAppImagePayload? = null,
)

@Serializable
data class WhatsAppConversationPayload(
    @SerialName("business_phone_number_id")
    @SerializedName("business_phone_number_id")
    val businessPhoneNumberId: String,
    @SerialName("conversation_id")
    @SerializedName("conversation_id")
    val conversationId: String,
    val message: WhatsAppMessagePayload,
)

object WhatsAppNormalizer {
    fun normalize(payload: WhatsAppConversationPayload): NormalizedIngestionMessage {
        val message = payload.message
        val attachments =
            listOfNotNull(message.image).map { image ->
                NormalizedAttachment(
                    attachmentId = image.id,
                    kind = AttachmentKind.IMAGE,
                    mimeType = image.mimeType,
                    fileReference = image.id,
                )
            }

        return NormalizedIngestionMessage(
            messageId = message.id,
            source =
                IngestionSource(
                    platform = IngestionPlatform.WHATSAPP,
                    sourceKind = IngestionSourceKind.BUSINESS_CONVERSATION,
                    sourceId = payload.conversationId,
                    workspaceId = payload.businessPhoneNumberId,
                ),
            senderId = message.from,
            text = message.textBody,
            occurredAt = java.time.Instant.ofEpochSecond(message.timestampSeconds).toString(),
            attachments = attachments,
        )
    }
}
