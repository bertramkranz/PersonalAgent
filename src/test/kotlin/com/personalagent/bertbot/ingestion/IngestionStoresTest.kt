package com.personalagent.bertbot.ingestion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IngestionStoresTest {
    @Test
    fun `consent store persists approvals and lists approved sources`() {
        val file = File.createTempFile("bertbot-ingestion-consent", ".json")
        file.delete()
        file.deleteOnExit()

        val source =
            IngestionSource(
                platform = IngestionPlatform.TELEGRAM,
                sourceKind = IngestionSourceKind.CHAT,
                sourceId = "chat-42",
            )

        val store = FileConsentStore(file)
        store.upsert(ApprovalRecord(source = source, scope = ApprovalScope.CHAT, approved = true))

        val reloaded = FileConsentStore(file)
        assertTrue(reloaded.isApproved(source))
        assertEquals(1, reloaded.listApproved().size)
    }

    @Test
    fun `consent store preserves unreadable file and resets entries`() {
        val file = File.createTempFile("bertbot-ingestion-consent", ".json")
        file.deleteOnExit()
        file.writeText("{not-valid-json")

        val store = FileConsentStore(file)

        assertTrue(store.load().isEmpty())
        val backups = file.parentFile?.listFiles { _, name -> name.startsWith("${file.nameWithoutExtension}.corrupt-") }
        assertTrue(backups?.isNotEmpty() == true)
    }

    @Test
    fun `source state store persists and retrieves sync cursor`() {
        val file = File.createTempFile("bertbot-ingestion-state", ".json")
        file.delete()
        file.deleteOnExit()

        val source =
            IngestionSource(
                platform = IngestionPlatform.SLACK,
                sourceKind = IngestionSourceKind.CHANNEL,
                sourceId = "C123",
                workspaceId = "T001",
            )

        val store = FileSourceStateStore(file)
        store.upsert(SyncCursor(source = source, cursor = "1700000000.1234"))

        val reloaded = FileSourceStateStore(file)
        val cursor = reloaded.find(source)
        assertNotNull(cursor)
        assertEquals("1700000000.1234", cursor.cursor)
    }
}
