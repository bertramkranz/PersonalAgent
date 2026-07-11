package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.AttachmentKind
import com.personalagent.bertbot.ingestion.IngestionPlatform
import com.personalagent.bertbot.ingestion.IngestionSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsAppNormalizerTest {
    @Test
    fun `normalizer maps business conversation ids and image references`() {
        val payload =
            WhatsAppConversationPayload(
                businessPhoneNumberId = "15551234567",
                conversationId = "wamid-conv-1",
                message =
                    WhatsAppMessagePayload(
                        id = "wamid-msg-9",
                        from = "15557654321",
                        timestampSeconds = 1_700_000_000,
                        textBody = "Inventory update",
                        image = WhatsAppImagePayload(id = "media-555", mimeType = "image/jpeg"),
                    ),
            )

        val normalized = WhatsAppNormalizer.normalize(payload)

        assertEquals(IngestionPlatform.WHATSAPP, normalized.source.platform)
        assertEquals(IngestionSourceKind.BUSINESS_CONVERSATION, normalized.source.sourceKind)
        assertEquals("15551234567", normalized.source.workspaceId)
        assertEquals("wamid-conv-1", normalized.source.sourceId)
        assertEquals("media-555", normalized.attachments.single().attachmentId)
        assertEquals(AttachmentKind.IMAGE, normalized.attachments.single().kind)
    }
}
