package com.personalagent.bertbot.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LearningReviewStoreTest {
    @Test
    fun `file learning review store isolates scopes and supports decisions`() {
        val file = File.createTempFile("bertbot-learning-review", ".jsonl")
        file.delete()
        file.deleteOnExit()

        val store = FileLearningReviewStore(file)

        val globalRequest =
            buildLearningReviewRequest(
                scopeKey = "global",
                writeType = LearningReviewWriteType.MEMORY,
                payload = "{\"kind\":\"memory\"}",
            )
        store.enqueue(globalRequest)

        val scopedId =
            store.withScope("scope-a") {
                val request =
                    buildLearningReviewRequest(
                        scopeKey = "scope-a",
                        writeType = LearningReviewWriteType.SKILL,
                        payload = "{\"kind\":\"skill\"}",
                    )
                store.enqueue(request)
                request.requestId
            }

        assertEquals(1, store.list(status = LearningReviewStatus.PENDING, limit = 20).size)

        val approved =
            store.withScope("scope-a") {
                store.decide(
                    requestId = scopedId,
                    status = LearningReviewStatus.APPROVED,
                    note = "safe",
                )
            }

        assertNotNull(approved)
        assertEquals(LearningReviewStatus.APPROVED, approved.status)
        assertEquals("safe", approved.decisionNote)

        val pendingAfterApprove =
            store.withScope("scope-a") {
                store.list(status = LearningReviewStatus.PENDING, limit = 20)
            }
        assertTrue(pendingAfterApprove.isEmpty())
    }

    @Test
    fun `file learning review store records apply failure metadata without changing pending status`() {
        val file = File.createTempFile("bertbot-learning-review-failure", ".jsonl")
        file.delete()
        file.deleteOnExit()

        val store = FileLearningReviewStore(file)
        val request =
            buildLearningReviewRequest(
                scopeKey = "scope-f",
                writeType = LearningReviewWriteType.SKILL,
                payload = "{\"kind\":\"skill\"}",
            )

        store.withScope("scope-f") {
            store.enqueue(request)
            val updated = store.recordApplyFailure(request.requestId, "service unavailable")
            assertNotNull(updated)
            assertEquals(LearningReviewStatus.PENDING, updated.status)
            assertEquals("service unavailable", updated.lastApplyFailureReason)
            assertNotNull(updated.lastApplyFailedAt)
        }
    }
}
