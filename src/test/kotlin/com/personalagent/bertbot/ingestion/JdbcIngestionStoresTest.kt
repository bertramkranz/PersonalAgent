package com.personalagent.bertbot.ingestion

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JdbcIngestionStoresTest {
    @Test
    fun `jdbc ingestion stores persist approvals and source cursors`() {
        val jdbcUrl = h2JdbcUrl()
        val consentStore = JdbcConsentStore(jdbcUrl = jdbcUrl, tableName = "bertbot_consent_snapshot")
        val sourceStateStore = JdbcSourceStateStore(jdbcUrl = jdbcUrl, tableName = "bertbot_source_snapshot")

        val source =
            IngestionSource(
                platform = IngestionPlatform.SLACK,
                sourceKind = IngestionSourceKind.CHANNEL,
                sourceId = "C123",
                workspaceId = "T001",
            )

        consentStore.upsert(ApprovalRecord(source = source, scope = ApprovalScope.CHANNEL, approved = true))
        sourceStateStore.upsert(SyncCursor(source = source, cursor = "cursor-1"))

        val reloadedConsent = JdbcConsentStore(jdbcUrl = jdbcUrl, tableName = "bertbot_consent_snapshot")
        val reloadedSourceState = JdbcSourceStateStore(jdbcUrl = jdbcUrl, tableName = "bertbot_source_snapshot")

        assertTrue(reloadedConsent.isApproved(source))
        assertEquals(1, reloadedConsent.listApproved().size)
        val loadedCursor = reloadedSourceState.find(source)
        assertNotNull(loadedCursor)
        assertEquals("cursor-1", loadedCursor.cursor)
    }

    @Test
    fun `jdbc ingestion stores preserve parallel updates across store instances`() {
        val jdbcUrl = h2JdbcUrl()
        val consentStoreA = JdbcConsentStore(jdbcUrl = jdbcUrl, tableName = "bertbot_consent_snapshot")
        val consentStoreB = JdbcConsentStore(jdbcUrl = jdbcUrl, tableName = "bertbot_consent_snapshot")
        val sourceStoreA = JdbcSourceStateStore(jdbcUrl = jdbcUrl, tableName = "bertbot_source_snapshot")
        val sourceStoreB = JdbcSourceStateStore(jdbcUrl = jdbcUrl, tableName = "bertbot_source_snapshot")

        val workerCount = 2
        val writesPerWorker = 20
        val start = CountDownLatch(1)
        val done = CountDownLatch(workerCount)
        val pool = Executors.newFixedThreadPool(workerCount)
        val futures = mutableListOf<Future<*>>()

        try {
            futures +=
                submitWorker(
                    pool = pool,
                    start = start,
                    done = done,
                    writesPerWorker = writesPerWorker,
                    config =
                        WorkerConfig(
                            sourcePrefix = "A",
                            cursorPrefix = "cursor-a",
                            consentStore = consentStoreA,
                            sourceStore = sourceStoreA,
                        ),
                )

            futures +=
                submitWorker(
                    pool = pool,
                    start = start,
                    done = done,
                    writesPerWorker = writesPerWorker,
                    config =
                        WorkerConfig(
                            sourcePrefix = "B",
                            cursorPrefix = "cursor-b",
                            consentStore = consentStoreB,
                            sourceStore = sourceStoreB,
                        ),
                )

            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val finalConsent = JdbcConsentStore(jdbcUrl = jdbcUrl, tableName = "bertbot_consent_snapshot")
        val finalSource = JdbcSourceStateStore(jdbcUrl = jdbcUrl, tableName = "bertbot_source_snapshot")

        assertEquals(workerCount * writesPerWorker, finalConsent.listApproved().size)
        val aCursor =
            finalSource.find(
                IngestionSource(
                    platform = IngestionPlatform.SLACK,
                    sourceKind = IngestionSourceKind.CHANNEL,
                    sourceId = "A-0",
                    workspaceId = "TEAM",
                ),
            )
        val bCursor =
            finalSource.find(
                IngestionSource(
                    platform = IngestionPlatform.SLACK,
                    sourceKind = IngestionSourceKind.CHANNEL,
                    sourceId = "B-0",
                    workspaceId = "TEAM",
                ),
            )

        assertNotNull(aCursor)
        assertNotNull(bCursor)
    }

    private fun submitWorker(
        pool: java.util.concurrent.ExecutorService,
        start: CountDownLatch,
        done: CountDownLatch,
        writesPerWorker: Int,
        config: WorkerConfig,
    ): Future<*> =
        pool.submit {
            try {
                start.await()
                repeat(writesPerWorker) { index ->
                    val source = buildChannelSource("${config.sourcePrefix}-$index")
                    config.consentStore.upsert(
                        ApprovalRecord(source = source, scope = ApprovalScope.CHANNEL, approved = true),
                    )
                    config.sourceStore.upsert(
                        SyncCursor(source = source, cursor = "${config.cursorPrefix}-$index"),
                    )
                }
            } finally {
                done.countDown()
            }
        }

    private fun buildChannelSource(sourceId: String): IngestionSource =
        IngestionSource(
            platform = IngestionPlatform.SLACK,
            sourceKind = IngestionSourceKind.CHANNEL,
            sourceId = sourceId,
            workspaceId = "TEAM",
        )

    private data class WorkerConfig(
        val sourcePrefix: String,
        val cursorPrefix: String,
        val consentStore: JdbcConsentStore,
        val sourceStore: JdbcSourceStateStore,
    )

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_ingestion_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
