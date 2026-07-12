package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import com.personalagent.bertbot.ingestion.ApprovalRecord
import com.personalagent.bertbot.ingestion.ApprovalUpdateRequest
import com.personalagent.bertbot.ingestion.IngestionControlPlane
import com.personalagent.bertbot.ingestion.IngestionDecision
import com.personalagent.bertbot.ingestion.IngestionOutcome
import com.personalagent.bertbot.ingestion.NormalizedIngestionMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRequestDispatcherIngestionTest {
    @Test
    fun `tools list remains additive when ingestion tools are enabled`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                ingestionControlPlane = FakeIngestionControlPlane(),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val tools = json.getAsJsonObject("result").getAsJsonArray("tools")
        val names = tools.map { it.asJsonObject.get("name").asString }
        assertTrue(names.contains("ask_bertbot"))
        assertTrue(names.contains("bertbot_status"))
        assertTrue(names.contains("ingestion_set_approval"))
        assertTrue(names.contains("ingestion_list_approved_sources"))
        assertTrue(names.contains("ingestion_ingest_manual"))
        assertTrue(names.contains("checkpoint_list"))
        assertTrue(names.contains("checkpoint_latest"))
        assertTrue(names.contains("checkpoint_get"))
        assertTrue(names.contains("checkpoint_rollback"))
        assertTrue(names.contains("checkpoint_rollback_latest"))
        assertTrue(names.contains("checkpoint_policy"))
    }

    @Test
    fun `ingestion approval and manual ingest tools route through control plane`() {
        val control = FakeIngestionControlPlane()
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                ingestionControlPlane = control,
                externalChatResponder = { message, dryRun ->
                    com.personalagent.bertbot.ingestion.ExternalChatOutcome(
                        inbound = message,
                        ingestion = IngestionOutcome(message, IngestionDecision.APPROVED, dryRun = dryRun),
                        outbound =
                            com.personalagent.bertbot.ingestion.NormalizedOutboundMessage(
                                source = message.source,
                                text = "reply from bertbot",
                                replyToMessageId = message.messageId,
                            ),
                        dryRun = dryRun,
                    )
                },
            )

        dispatcher.handle(
            """
            {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"ingestion_set_approval","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-77","approved":true}}}
            """.trimIndent(),
        )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"ingestion_ingest_manual","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-77","text":"hello","dryRun":true}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("decision=approved"))
        assertEquals(1, control.manualIngestCalls)

        val chatResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"ingestion_chat_manual","arguments":{"platform":"telegram","sourceKind":"chat","sourceId":"chat-77","text":"hello"}}}
                """.trimIndent(),
            )
        val chatJson = JsonParser.parseString(chatResponse).asJsonObject
        val chatText = chatJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(chatText.contains("reply from bertbot"))
    }
}

private class FakeIngestionControlPlane : IngestionControlPlane {
    private val approvals = mutableListOf<ApprovalRecord>()
    var manualIngestCalls: Int = 0

    override fun setApproval(request: ApprovalUpdateRequest): ApprovalRecord {
        val record = ApprovalRecord(source = request.source, scope = request.scope, approved = request.approved)
        approvals.removeAll { it.source == request.source }
        approvals.add(record)
        return record
    }

    override fun listApprovedSources(): List<ApprovalRecord> = approvals.filter { it.approved }

    override fun ingestManual(
        messages: List<NormalizedIngestionMessage>,
        dryRun: Boolean,
    ): List<IngestionOutcome> {
        manualIngestCalls += 1
        return messages.map { message ->
            IngestionOutcome(
                message = message,
                decision = IngestionDecision.APPROVED,
                dryRun = dryRun,
            )
        }
    }
}
