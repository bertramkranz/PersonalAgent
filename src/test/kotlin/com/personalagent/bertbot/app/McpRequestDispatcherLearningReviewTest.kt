package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRequestDispatcherLearningReviewTest {
    @Test
    fun `tools list includes learning review tools when router is configured`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                learningReviewToolRouter =
                    LearningReviewToolRouter(
                        listPending = { _, _ -> emptyList() },
                        approve = { _, _, _ -> LearningReviewDecisionOutcome() },
                        reject = { _, _, _ -> LearningReviewDecisionOutcome() },
                    ),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2201,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val names =
            json
                .getAsJsonObject("result")
                .getAsJsonArray("tools")
                .map { it.asJsonObject.get("name").asString }

        assertTrue(names.contains("learning_review_list_pending"))
        assertTrue(names.contains("learning_review_approve"))
        assertTrue(names.contains("learning_review_reject"))
    }

    @Test
    fun `learning review tools route list and approve calls`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                learningReviewToolRouter =
                    LearningReviewToolRouter(
                        listPending = { _, _ ->
                            listOf(
                                LearningReviewRequest(
                                    requestId = "req-101",
                                    createdAt = "2026-08-04T10:00:00Z",
                                    scopeKey = "scope-a",
                                    writeType = LearningReviewWriteType.MEMORY,
                                    payload = "{}",
                                ),
                            )
                        },
                        approve = { requestId, scopeKey, note ->
                            LearningReviewDecisionOutcome(
                                request =
                                    LearningReviewRequest(
                                        requestId = requestId,
                                        createdAt = "2026-08-04T10:00:00Z",
                                        scopeKey = scopeKey ?: "global",
                                        writeType = LearningReviewWriteType.MEMORY,
                                        payload = "{}",
                                        status = LearningReviewStatus.APPROVED,
                                        decisionNote = note,
                                    ),
                            )
                        },
                        reject = { _, _, _ -> LearningReviewDecisionOutcome() },
                    ),
            )

        val listResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2202,"method":"tools/call","params":{"name":"learning_review_list_pending","arguments":{"scopeKey":"scope-a","limit":50}}}
                """.trimIndent(),
            )
        val listJson = JsonParser.parseString(listResponse).asJsonObject
        val listText = listJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(listText.contains("requestId=req-101"))
        assertFalse(listJson.getAsJsonObject("result").get("isError").asBoolean)

        val approveResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2203,"method":"tools/call","params":{"name":"learning_review_approve","arguments":{"requestId":"req-101","scopeKey":"scope-a","note":"ok"}}}
                """.trimIndent(),
            )
        val approveJson = JsonParser.parseString(approveResponse).asJsonObject
        val approveText = approveJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(approveText.contains("Approved"))
        assertFalse(approveJson.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    @Suppress("LongMethod")
    fun `learning review approve can fail then succeed and surfaces failure metadata while pending`() {
        val pending =
            mutableListOf(
                LearningReviewRequest(
                    requestId = "req-retry",
                    createdAt = "2026-08-04T10:00:00Z",
                    scopeKey = "scope-a",
                    writeType = LearningReviewWriteType.SKILL,
                    payload = "{}",
                ),
            )
        var approveAttempts = 0

        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                learningReviewToolRouter =
                    LearningReviewToolRouter(
                        listPending = { _, _ -> pending.toList() },
                        approve = { requestId, _, _ ->
                            approveAttempts += 1
                            val index = pending.indexOfFirst { it.requestId == requestId }
                            if (index < 0) {
                                return@LearningReviewToolRouter LearningReviewDecisionOutcome(
                                    request = null,
                                    message = "Learning review request not found.",
                                )
                            }

                            if (approveAttempts == 1) {
                                val failed =
                                    pending[index].copy(
                                        lastApplyFailedAt = "2026-08-04T10:05:00Z",
                                        lastApplyFailureReason = "research service unavailable",
                                    )
                                pending[index] = failed
                                LearningReviewDecisionOutcome(
                                    request = null,
                                    message = "research service unavailable",
                                )
                            } else {
                                val approved = pending[index].copy(status = LearningReviewStatus.APPROVED)
                                pending.removeAt(index)
                                LearningReviewDecisionOutcome(request = approved)
                            }
                        },
                        reject = { _, _, _ -> LearningReviewDecisionOutcome() },
                    ),
            )

        val firstApproveResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2301,"method":"tools/call","params":{"name":"learning_review_approve","arguments":{"requestId":"req-retry","scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val firstApproveJson = JsonParser.parseString(firstApproveResponse).asJsonObject
        val firstApproveText =
            firstApproveJson
                .getAsJsonObject("result")
                .getAsJsonArray("content")[0]
                .asJsonObject
                .get("text")
                .asString
        assertTrue(firstApproveJson.getAsJsonObject("result").get("isError").asBoolean)
        assertContains(firstApproveText, "research service unavailable")

        val pendingAfterFailureResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2302,"method":"tools/call","params":{"name":"learning_review_list_pending","arguments":{"scopeKey":"scope-a","limit":50}}}
                """.trimIndent(),
            )
        val pendingAfterFailureJson = JsonParser.parseString(pendingAfterFailureResponse).asJsonObject
        val pendingAfterFailureText =
            pendingAfterFailureJson
                .getAsJsonObject("result")
                .getAsJsonArray("content")[0]
                .asJsonObject
                .get("text")
                .asString
        assertFalse(pendingAfterFailureJson.getAsJsonObject("result").get("isError").asBoolean)
        assertContains(pendingAfterFailureText, "requestId=req-retry")
        assertContains(pendingAfterFailureText, "lastApplyFailedAt=2026-08-04T10:05:00Z")
        assertContains(pendingAfterFailureText, "lastApplyFailureReason=research service unavailable")

        val secondApproveResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2303,"method":"tools/call","params":{"name":"learning_review_approve","arguments":{"requestId":"req-retry","scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val secondApproveJson = JsonParser.parseString(secondApproveResponse).asJsonObject
        val secondApproveText =
            secondApproveJson
                .getAsJsonObject("result")
                .getAsJsonArray("content")[0]
                .asJsonObject
                .get("text")
                .asString
        assertFalse(secondApproveJson.getAsJsonObject("result").get("isError").asBoolean)
        assertContains(secondApproveText, "Approved learning-review request req-retry")

        val finalListResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2304,"method":"tools/call","params":{"name":"learning_review_list_pending","arguments":{"scopeKey":"scope-a","limit":50}}}
                """.trimIndent(),
            )
        val finalListJson = JsonParser.parseString(finalListResponse).asJsonObject
        val finalListText =
            finalListJson
                .getAsJsonObject("result")
                .getAsJsonArray("content")[0]
                .asJsonObject
                .get("text")
                .asString
        assertFalse(finalListJson.getAsJsonObject("result").get("isError").asBoolean)
        assertContains(finalListText, "No pending learning-review requests found")
    }
}
