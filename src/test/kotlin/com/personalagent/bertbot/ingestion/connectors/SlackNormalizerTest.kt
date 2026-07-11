package com.personalagent.bertbot.ingestion.connectors

import com.personalagent.bertbot.ingestion.AttachmentKind
import com.personalagent.bertbot.ingestion.IngestionPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackNormalizerTest {
    @Test
    fun `normalizer maps slack channel thread and file references`() {
        val payload =
            SlackEnvelopePayload(
                teamId = "T001",
                event =
                    SlackMessageEventPayload(
                        ts = "1700000000.123456",
                        channel = "C222",
                        user = "U777",
                        text = "Please review this",
                        threadTs = "1700000000.000001",
                        files =
                            listOf(
                                SlackFilePayload(
                                    id = "F111",
                                    name = "screenshot.png",
                                    mimetype = "image/png",
                                    urlPrivate = "https://files.slack.com/F111",
                                    size = 4096,
                                ),
                            ),
                    ),
            )

        val normalized = SlackNormalizer.normalize(payload)

        assertEquals(IngestionPlatform.SLACK, normalized.source.platform)
        assertEquals("T001", normalized.source.workspaceId)
        assertEquals("C222", normalized.source.sourceId)
        assertEquals("1700000000.000001", normalized.threadId)
        assertEquals("U777", normalized.senderId)
        assertEquals("F111", normalized.attachments.single().attachmentId)
        assertEquals(AttachmentKind.IMAGE, normalized.attachments.single().kind)
    }
}
