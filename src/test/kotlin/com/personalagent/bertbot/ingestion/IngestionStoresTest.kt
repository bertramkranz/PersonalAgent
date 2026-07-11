package com.personalagent.bertbot.ingestion

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun `consent and source state stores preserve all updates under concurrent writes`() {
        val consentFile = File.createTempFile("bertbot-ingestion-consent", ".json")
        val sourceFile = File.createTempFile("bertbot-ingestion-state", ".json")
        consentFile.delete()
        sourceFile.delete()
        consentFile.deleteOnExit()
        sourceFile.deleteOnExit()

        val consentStore = FileConsentStore(consentFile)
        val sourceStore = FileSourceStateStore(sourceFile)
        val workerCount = 5
        val writesPerWorker = 12
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            repeat(workerCount) { worker ->
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(writesPerWorker) { index ->
                            val source =
                                IngestionSource(
                                    platform = IngestionPlatform.SLACK,
                                    sourceKind = IngestionSourceKind.CHANNEL,
                                    sourceId = "C$worker-$index",
                                    workspaceId = "T001",
                                )
                            consentStore.upsert(ApprovalRecord(source = source, scope = ApprovalScope.CHANNEL, approved = true))
                            sourceStore.upsert(SyncCursor(source = source, cursor = "cursor-$worker-$index"))
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        val approved = consentStore.listApproved()
        assertEquals(workerCount * writesPerWorker, approved.size)
        assertTrue(consentFile.readText().contains("\"schemaVersion\":1"))

        val cursors = sourceStore.load()
        assertEquals(workerCount * writesPerWorker, cursors.size)
        assertTrue(sourceFile.readText().contains("\"schemaVersion\":1"))
    }
}
