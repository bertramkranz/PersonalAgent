package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.AttachmentKind
import com.personalagent.bertbot.ingestion.IngestionPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TelegramNormalizerTest {
    @Test
    fun `normalizer maps telegram ids timestamps and file ids`() {
        val update =
            TelegramUpdatePayload(
                updateId = "1",
                message =
                    TelegramMessagePayload(
                        messageId = "msg-7",
                        dateEpochSeconds = 1_700_000_000,
                        chat = TelegramChatPayload(id = "chat-42"),
                        from = TelegramUserPayload(id = "u1", firstName = "Ada", lastName = "Lovelace"),
                        caption = "Photo note",
                        photos = listOf(TelegramPhotoPayload(fileId = "file-abc", width = 640, height = 480)),
                    ),
            )

        val normalized = TelegramNormalizer.normalize(update)

        assertNotNull(normalized)
        assertEquals(IngestionPlatform.TELEGRAM, normalized.source.platform)
        assertEquals("chat-42", normalized.source.sourceId)
        assertEquals("u1", normalized.senderId)
        assertEquals("Ada Lovelace", normalized.senderDisplayName)
        assertEquals("Photo note", normalized.text)
        assertEquals("file-abc", normalized.attachments.single().attachmentId)
        assertEquals(AttachmentKind.IMAGE, normalized.attachments.single().kind)
    }
}
