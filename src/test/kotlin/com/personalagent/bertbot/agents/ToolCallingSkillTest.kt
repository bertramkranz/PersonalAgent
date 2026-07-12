package com.personalagent.bertbot.agents

import com.google.gson.JsonObject
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallingSkillTest {
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

    private fun toolDef(name: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("name", name)
        obj.addProperty("description", "Test tool")
        return obj
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
