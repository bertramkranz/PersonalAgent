package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHistoryToolRouterTest {
    @Test
    fun `tool definitions expose list and clear tools`() {
        val router =
            SessionHistoryToolRouter(
                listEntries = { _, _ -> emptyList() },
                searchEntries = { _, _, _ -> emptyList() },
                clearEntries = { true },
            )

        val names = router.toolDefinitions().map { it.get("name").asString }.toSet()
        assertTrue(names.contains(SESSION_HISTORY_LIST_TOOL_NAME))
        assertTrue(names.contains(SESSION_HISTORY_SEARCH_TOOL_NAME))
        assertTrue(names.contains(SESSION_HISTORY_CLEAR_TOOL_NAME))
    }

    @Test
    fun `list returns rendered history for bounded limit`() {
        var capturedLimit = -1
        var capturedScope: String? = null
        val router =
            SessionHistoryToolRouter(
                listEntries = { limit, scopeKey ->
                    capturedLimit = limit
                    capturedScope = scopeKey
                    listOf(
                        SessionHistoryEntry(
                            timestamp = "2026-08-04T10:00:00Z",
                            role = SessionHistoryRole.USER,
                            text = "hello",
                            traceId = "trace-1",
                        ),
                    )
                },
                searchEntries = { _, _, _ -> emptyList() },
                clearEntries = { true },
            )

        val params =
            JsonObject().apply {
                add(
                    "arguments",
                    JsonObject().apply {
                        addProperty("limit", 9_999)
                        addProperty("scopeKey", "external_scope")
                    },
                )
            }
        val result =
            router.handle(SESSION_HISTORY_LIST_TOOL_NAME, params)

        assertNotNull(result)
        assertEquals(false, result.first)
        assertEquals(1_000, capturedLimit)
        assertEquals("external_scope", capturedScope)
        assertContains(result.second, "USER: hello")
        assertContains(result.second, "traceId=trace-1")
    }

    @Test
    fun `clear requires confirm flag`() {
        var clearCalled = false
        val router =
            SessionHistoryToolRouter(
                listEntries = { _, _ -> emptyList() },
                searchEntries = { _, _, _ -> emptyList() },
                clearEntries = {
                    clearCalled = true
                    true
                },
            )

        val missingConfirm = JsonObject().apply { add("arguments", JsonObject()) }
        val rejected = router.handle(SESSION_HISTORY_CLEAR_TOOL_NAME, missingConfirm)

        assertNotNull(rejected)
        assertEquals(true, rejected.first)
        assertContains(rejected.second, "confirm=true")
        assertEquals(false, clearCalled)

        val confirmed =
            JsonObject().apply {
                add(
                    "arguments",
                    JsonObject().apply {
                        addProperty("confirm", true)
                        addProperty("scopeKey", "scope-a")
                    },
                )
            }
        val accepted =
            router.handle(SESSION_HISTORY_CLEAR_TOOL_NAME, confirmed)

        assertNotNull(accepted)
        assertEquals(false, accepted.first)
        assertContains(accepted.second, "Session history cleared")
        assertEquals(true, clearCalled)
    }

    @Test
    fun `unknown tool returns null`() {
        val router =
            SessionHistoryToolRouter(
                listEntries = { _, _ -> emptyList() },
                searchEntries = { _, _, _ -> emptyList() },
                clearEntries = { true },
            )

        assertNull(router.handle("unknown", JsonObject()))
    }

    @Test
    fun `search returns rendered history for bounded limit`() {
        var capturedQuery = ""
        var capturedLimit = -1
        var capturedScope: String? = null
        val router =
            SessionHistoryToolRouter(
                listEntries = { _, _ -> emptyList() },
                searchEntries = { query, limit, scopeKey ->
                    capturedQuery = query
                    capturedLimit = limit
                    capturedScope = scopeKey
                    listOf(
                        SessionHistoryEntry(
                            timestamp = "2026-08-04T10:00:00Z",
                            role = SessionHistoryRole.ASSISTANT,
                            text = "search match",
                            traceId = "trace-search",
                        ),
                    )
                },
                clearEntries = { true },
            )

        val params =
            JsonObject().apply {
                add(
                    "arguments",
                    JsonObject().apply {
                        addProperty("query", "match")
                        addProperty("limit", 9_999)
                        addProperty("scopeKey", "external_scope")
                    },
                )
            }
        val result =
            router.handle(SESSION_HISTORY_SEARCH_TOOL_NAME, params)

        assertNotNull(result)
        assertEquals(false, result.first)
        assertEquals("match", capturedQuery)
        assertEquals(1_000, capturedLimit)
        assertEquals("external_scope", capturedScope)
        assertContains(result.second, "ASSISTANT: search match")
        assertContains(result.second, "traceId=trace-search")
    }
}
