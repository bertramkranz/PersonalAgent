package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningReviewToolRouterTest {
    @Test
    fun `tool definitions expose learning review tools`() {
        val router =
            LearningReviewToolRouter(
                listPending = { _, _ -> emptyList() },
                approve = { _, _, _ -> LearningReviewDecisionOutcome() },
                reject = { _, _, _ -> LearningReviewDecisionOutcome() },
            )

        val names = router.toolDefinitions().map { it.get("name").asString }.toSet()
        assertTrue(names.contains(LEARNING_REVIEW_LIST_TOOL_NAME))
        assertTrue(names.contains(LEARNING_REVIEW_APPROVE_TOOL_NAME))
        assertTrue(names.contains(LEARNING_REVIEW_REJECT_TOOL_NAME))
    }

    @Test
    fun `list returns rendered pending requests`() {
        val router =
            LearningReviewToolRouter(
                listPending = { _, _ ->
                    listOf(
                        LearningReviewRequest(
                            requestId = "req-1",
                            createdAt = "2026-08-04T10:00:00Z",
                            scopeKey = "scope-a",
                            writeType = LearningReviewWriteType.MEMORY,
                            payload = "{}",
                            traceId = "trace-1",
                            lastApplyFailedAt = "2026-08-04T10:05:00Z",
                            lastApplyFailureReason = "transient failure",
                        ),
                    )
                },
                approve = { _, _, _ -> LearningReviewDecisionOutcome() },
                reject = { _, _, _ -> LearningReviewDecisionOutcome() },
            )

        val response = router.handle(LEARNING_REVIEW_LIST_TOOL_NAME, JsonObject().apply { add("arguments", JsonObject()) })
        assertNotNull(response)
        assertEquals(false, response.first)
        assertContains(response.second, "requestId=req-1")
        assertContains(response.second, "type=MEMORY")
        assertContains(response.second, "lastApplyFailedAt=2026-08-04T10:05:00Z")
        assertContains(response.second, "lastApplyFailureReason=transient failure")
    }

    @Test
    fun `approve and reject require request id`() {
        val router =
            LearningReviewToolRouter(
                listPending = { _, _ -> emptyList() },
                approve = { _, _, _ -> LearningReviewDecisionOutcome() },
                reject = { _, _, _ -> LearningReviewDecisionOutcome() },
            )

        val approveMissing = router.handle(LEARNING_REVIEW_APPROVE_TOOL_NAME, JsonObject().apply { add("arguments", JsonObject()) })
        assertNotNull(approveMissing)
        assertEquals(true, approveMissing.first)
        assertContains(approveMissing.second, "requestId")

        val rejectMissing = router.handle(LEARNING_REVIEW_REJECT_TOOL_NAME, JsonObject().apply { add("arguments", JsonObject()) })
        assertNotNull(rejectMissing)
        assertEquals(true, rejectMissing.first)
        assertContains(rejectMissing.second, "requestId")
    }

    @Test
    fun `approve and reject return success when request is found`() {
        val decided =
            LearningReviewRequest(
                requestId = "req-9",
                createdAt = "2026-08-04T10:00:00Z",
                scopeKey = "scope-a",
                writeType = LearningReviewWriteType.SKILL,
                payload = "{}",
                status = LearningReviewStatus.APPROVED,
            )
        val router =
            LearningReviewToolRouter(
                listPending = { _, _ -> emptyList() },
                approve = { _, _, _ -> LearningReviewDecisionOutcome(request = decided) },
                reject = { requestId, scopeKey, note ->
                    LearningReviewDecisionOutcome(
                        request =
                            decided.copy(
                                requestId = requestId,
                                scopeKey = scopeKey ?: "global",
                                status = LearningReviewStatus.REJECTED,
                                decisionNote = note,
                            ),
                    )
                },
            )

        val approveArgs =
            JsonObject().apply {
                add("arguments", JsonObject().apply { addProperty("requestId", "req-9") })
            }
        val approveResult =
            router.handle(LEARNING_REVIEW_APPROVE_TOOL_NAME, approveArgs)
        assertNotNull(approveResult)
        assertEquals(false, approveResult.first)
        assertContains(approveResult.second, "Approved")

        val rejectArgs =
            JsonObject().apply {
                add(
                    "arguments",
                    JsonObject().apply {
                        addProperty("requestId", "req-9")
                        addProperty("scopeKey", "scope-a")
                        addProperty("note", "not needed")
                    },
                )
            }
        val rejectResult =
            router.handle(LEARNING_REVIEW_REJECT_TOOL_NAME, rejectArgs)
        assertNotNull(rejectResult)
        assertEquals(false, rejectResult.first)
        assertContains(rejectResult.second, "Rejected")
    }

    @Test
    fun `unknown tool returns null`() {
        val router =
            LearningReviewToolRouter(
                listPending = { _, _ -> emptyList() },
                approve = { _, _, _ -> LearningReviewDecisionOutcome() },
                reject = { _, _, _ -> LearningReviewDecisionOutcome() },
            )

        assertNull(router.handle("unknown", JsonObject()))
    }

    @Test
    fun `approve returns explicit apply-failure message`() {
        val router =
            LearningReviewToolRouter(
                listPending = { _, _ -> emptyList() },
                approve = { _, _, _ -> LearningReviewDecisionOutcome(request = null, message = "apply failed") },
                reject = { _, _, _ -> LearningReviewDecisionOutcome() },
            )

        val approveArgs =
            JsonObject().apply {
                add("arguments", JsonObject().apply { addProperty("requestId", "req-fail") })
            }
        val approveResult =
            router.handle(LEARNING_REVIEW_APPROVE_TOOL_NAME, approveArgs)

        assertNotNull(approveResult)
        assertEquals(true, approveResult.first)
        assertContains(approveResult.second, "apply failed")
    }
}
