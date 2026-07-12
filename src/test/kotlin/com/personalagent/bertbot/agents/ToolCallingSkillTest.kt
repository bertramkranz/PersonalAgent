package com.personalagent.bertbot.agents

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallingSkillTest {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    @Test
    fun `responds directly when no tool call needed`() {
        val gateway = SequenceLlmGateway(listOf("""{"action":"respond","response":"Hello!"}"""))
        val skill = ToolCallingSkill(gateway, emptyList(), { _, _ -> "unused" })

        val response = skill.invoke("System", "User question", TracingContext())

        assertEquals("Hello!", response)
        assertEquals(1, gateway.callCount)
    }

    @Test
    fun `calls tool then returns final answer`() {
        val gateway =
            SequenceLlmGateway(
                listOf(
                    """{"action":"call_tool","tool":"test_tool","arguments":{"param":"value"}}""",
                    """{"action":"respond","response":"Final answer"}""",
                ),
            )
        var toolExecuted = false
        var capturedName = ""
        val skill =
            ToolCallingSkill(gateway, listOf(toolDef("test_tool")), { name, args ->
                toolExecuted = true
                capturedName = name
                "Tool result"
            })

        val response = skill.invoke("System", "User question", TracingContext())

        assertEquals("Final answer", response)
        assertTrue(toolExecuted)
        assertEquals("test_tool", capturedName)
        assertEquals(2, gateway.callCount)
    }

    @Test
    fun `falls back gracefully on max iterations`() {
        val gateway =
            SequenceLlmGateway(
                listOf(
                    """{"action":"call_tool","tool":"t1","arguments":{}}""",
                    """{"action":"call_tool","tool":"t2","arguments":{}}""",
                    """{"action":"call_tool","tool":"t3","arguments":{}}""",
                    """No action taken""",
                ),
            )
        val skill =
            ToolCallingSkill(
                gateway,
                emptyList(),
                { _, _ -> "Tool result" },
                maxIterations = 3,
            )

        val response = skill.invoke("System", "User question", TracingContext())

        assertTrue(response.isNotEmpty())
        assertEquals("No action taken", response)
    }

    @Test
    fun `handles tool executor error gracefully`() {
        val gateway =
            SequenceLlmGateway(
                listOf(
                    """{"action":"call_tool","tool":"failing_tool","arguments":{}}""",
                    """{"action":"respond","response":"Handled error"}""",
                ),
            )
        val skill =
            ToolCallingSkill(gateway, listOf(toolDef("failing_tool")), { _, _ ->
                throw IllegalStateException("Tool failed!")
            })

        val response = skill.invoke("System", "User question", TracingContext())

        assertEquals("Handled error", response)
        assertEquals(2, gateway.callCount)
    }

    @Test
    fun `falls back to raw output when parse fails`() {
        val gateway = SequenceLlmGateway(listOf("This is not valid JSON"))
        val skill = ToolCallingSkill(gateway, emptyList(), { _, _ -> "unused" })

        val response = skill.invoke("System", "User question", TracingContext())

        assertEquals("This is not valid JSON", response)
    }

    @Test
    fun `formats calendar events from mcp tool result envelope`() {
        val gateway =
            SequenceLlmGateway(
                listOf(
                    """{"action":"call_tool","tool":"google_workspace_calendar_listEvents","arguments":{}}""",
                    """{"action":"respond","response":"Here are the events"}""",
                ),
            )
        val skill =
            ToolCallingSkill(
                gateway,
                listOf(toolDef("google_workspace_calendar_listEvents")),
                { _, _ ->
                    mcpToolResult(
                        """{"events":[{"summary":"Team Sync","start":{"dateTime":"2026-07-12T10:00:00Z"},"end":{"dateTime":"2026-07-12T10:30:00Z"},"description":"Planning"}]}""",
                    )
                },
            )

        val response = skill.invoke("System", "User question", TracingContext())

        assertContains(response, "Calendar Events")
        assertContains(response, "Team Sync")
        assertContains(response, "Start: 2026-07-12T10:00:00Z")
    }

    @Test
    fun `formats calendar list from mcp tool result envelope`() {
        val gateway =
            SequenceLlmGateway(
                listOf(
                    """{"action":"call_tool","tool":"google_workspace_calendar_listCalendars","arguments":{}}""",
                    """{"action":"respond","response":"Here are the calendars"}""",
                ),
            )
        val skill =
            ToolCallingSkill(
                gateway,
                listOf(toolDef("google_workspace_calendar_listCalendars")),
                { _, _ ->
                    mcpToolResult(
                        """{"calendars":[{"id":"primary","summary":"Personal","primary":true}]}""",
                    )
                },
            )

        val response = skill.invoke("System", "User question", TracingContext())

        assertContains(response, "Your Calendars")
        assertContains(response, "Personal")
        assertContains(response, "Primary")
    }

    private fun toolDef(name: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("name", name)
        obj.addProperty("description", "Test tool")
        return obj
    }

    private fun mcpToolResult(text: String): String {
        val result = JsonObject()
        val content = JsonArray()
        val textContent = JsonObject()
        textContent.addProperty("type", "text")
        textContent.addProperty("text", text)
        content.add(textContent)
        result.add("content", content)
        result.addProperty("isError", false)
        return gson.toJson(result)
    }

    private class SequenceLlmGateway(private val responses: List<String>) : LlmGateway {
        var callCount = 0
            private set

        override fun complete(
            systemPrompt: String,
            userPrompt: String,
        ): String {
            val response = responses.getOrNull(callCount) ?: "No response"
            callCount++
            return response
        }
    }
}
