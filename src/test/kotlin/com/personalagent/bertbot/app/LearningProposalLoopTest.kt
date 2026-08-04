package com.personalagent.bertbot.app

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearningProposalLoopTest {
    @Test
    fun `proposal loop is disabled by default and does not enqueue without tick`() {
        val signalFile = File.createTempFile("bertbot-learning-proposal-signal", ".json")
        val reviewFile = File.createTempFile("bertbot-learning-review", ".jsonl")
        signalFile.delete()
        reviewFile.delete()
        signalFile.deleteOnExit()
        reviewFile.deleteOnExit()

        val signalStore = FileLearningProposalSignalStore(signalFile)
        val reviewStore = FileLearningReviewStore(reviewFile)
        val service =
            LearningProposalLoopService(
                signalStore = signalStore,
                learningReviewStore = reviewStore,
                configuration = LearningProposalRuntimeConfiguration(enabled = false),
            )

        service.start()
        signalStore.withScope("global") { signalStore.upsert("explicit_preference", incrementBy = 3) }

        val pending = reviewStore.withScope("global") { reviewStore.list(status = LearningReviewStatus.PENDING, limit = 20) }
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `proposal loop enqueues deduped proposals with cooldown`() {
        val signalFile = File.createTempFile("bertbot-learning-proposal-signal", ".json")
        val reviewFile = File.createTempFile("bertbot-learning-review", ".jsonl")
        signalFile.delete()
        reviewFile.delete()
        signalFile.deleteOnExit()
        reviewFile.deleteOnExit()

        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        var clock = now

        val signalStore = FileLearningProposalSignalStore(signalFile)
        val reviewStore = FileLearningReviewStore(reviewFile)
        val service =
            LearningProposalLoopService(
                signalStore = signalStore,
                learningReviewStore = reviewStore,
                configuration =
                    LearningProposalRuntimeConfiguration(
                        enabled = true,
                        maxBatchSize = 2,
                        cooldownMinutes = 60,
                    ),
                now = { clock },
            )

        service.recordSignal("scope-a", "explicit_preference", incrementBy = 3)
        service.recordSignal("scope-a", "user_correction", incrementBy = 2)
        service.recordSignal("scope-a", "incident_recovery_pattern", incrementBy = 5)

        val producedFirst = service.tick("scope-a")
        assertEquals(2, producedFirst)

        val firstPending = reviewStore.withScope("scope-a") { reviewStore.list(status = LearningReviewStatus.PENDING, limit = 20) }
        assertEquals(2, firstPending.size)

        val producedSecond = service.tick("scope-a")
        assertEquals(1, producedSecond)

        clock = clock.plus(2, ChronoUnit.HOURS)
        val producedThird = service.tick("scope-a")
        assertEquals(2, producedThird)
    }

    @Test
    fun `jdbc signal store upsert and markQueued are scope isolated`() {
        val store =
            LearningProposalSignalStoreFactory.create(
                PersistenceRuntimeConfiguration(
                    backend = "jdbc",
                    jdbcUrl = "jdbc:h2:mem:bertbot_learning_proposal_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    learningProposalSignalJdbcTable = "bertbot_learning_proposal_signal_snapshot",
                ),
            )

        store.withScope("scope-a") { store.upsert("explicit_preference", incrementBy = 2) }
        store.withScope("scope-b") { store.upsert("explicit_preference", incrementBy = 1) }

        val scopeA = store.withScope("scope-a") { store.list(limit = 10) }
        val scopeB = store.withScope("scope-b") { store.list(limit = 10) }

        assertEquals(1, scopeA.size)
        assertEquals(2, scopeA.first().count)
        assertEquals(1, scopeB.size)
        assertEquals(1, scopeB.first().count)
    }
}
