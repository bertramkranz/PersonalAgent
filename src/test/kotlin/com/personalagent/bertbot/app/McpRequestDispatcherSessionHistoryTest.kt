package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRequestDispatcherSessionHistoryTest {
    @Test
    fun `tools list includes session history tools when router is configured`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                sessionHistoryToolRouter =
                    SessionHistoryToolRouter(
                        listEntries = { _, _ -> emptyList() },
                        searchEntries = { _, _, _ -> emptyList() },
                        clearEntries = { true },
                    ),
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2101,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val names =
            json
                .getAsJsonObject("result")
                .getAsJsonArray("tools")
                .map { it.asJsonObject.get("name").asString }

        assertTrue(names.contains("session_history_list"))
        assertTrue(names.contains("session_history_search"))
        assertTrue(names.contains("session_history_clear"))
    }

    @Test
    @Suppress("LongMethod")
    fun `session history tools route list and clear calls`() {
        var clearedScope: String? = null
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { _, _ -> "unused" },
                sessionHistoryToolRouter =
                    SessionHistoryToolRouter(
                        listEntries = { _, scopeKey ->
                            listOf(
                                SessionHistoryEntry(
                                    timestamp = "2026-08-04T10:00:00Z",
                                    role = SessionHistoryRole.USER,
                                    text = "hi from ${scopeKey ?: "global"}",
                                    traceId = "trace-9",
                                ),
                            )
                        },
                        searchEntries = { query, _, scopeKey ->
                            listOf(
                                SessionHistoryEntry(
                                    timestamp = "2026-08-04T10:01:00Z",
                                    role = SessionHistoryRole.ASSISTANT,
                                    text = "search result for $query in ${scopeKey ?: "global"}",
                                    traceId = "trace-search",
                                ),
                            )
                        },
                        clearEntries = { scopeKey ->
                            clearedScope = scopeKey
                            true
                        },
                    ),
            )

        val listResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2102,"method":"tools/call","params":{"name":"session_history_list","arguments":{"scopeKey":"scope-a","limit":50}}}
                """.trimIndent(),
            )
        val listJson = JsonParser.parseString(listResponse).asJsonObject
        val listText = listJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(listText.contains("USER: hi from scope-a"))
        assertFalse(listJson.getAsJsonObject("result").get("isError").asBoolean)

        val searchResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":21025,"method":"tools/call","params":{"name":"session_history_search","arguments":{"scopeKey":"scope-a","query":"result","limit":50}}}
                """.trimIndent(),
            )
        val searchJson = JsonParser.parseString(searchResponse).asJsonObject
        val searchText = searchJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(searchText.contains("ASSISTANT: search result for result in scope-a"))
        assertFalse(searchJson.getAsJsonObject("result").get("isError").asBoolean)

        val clearRejectedResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2103,"method":"tools/call","params":{"name":"session_history_clear","arguments":{"scopeKey":"scope-a"}}}
                """.trimIndent(),
            )
        val clearRejectedJson = JsonParser.parseString(clearRejectedResponse).asJsonObject
        val clearRejectedText = clearRejectedJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(clearRejectedText.contains("confirm=true"))
        assertTrue(clearRejectedJson.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals(null, clearedScope)

        val clearAcceptedResponse =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2104,"method":"tools/call","params":{"name":"session_history_clear","arguments":{"scopeKey":"scope-a","confirm":true}}}
                """.trimIndent(),
            )
        val clearAcceptedJson = JsonParser.parseString(clearAcceptedResponse).asJsonObject
        val clearAcceptedText = clearAcceptedJson.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(clearAcceptedText.contains("Session history cleared"))
        assertFalse(clearAcceptedJson.getAsJsonObject("result").get("isError").asBoolean)
        assertEquals("scope-a", clearedScope)
    }
}
