package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRequestDispatcherTest {
    @Test
    fun `initialize returns protocol metadata`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { "unused" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        assertEquals(1, json.get("id").asInt)
        assertEquals("2024-11-05", json.getAsJsonObject("result").get("protocolVersion").asString)
        assertEquals("bertbot", json.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").asString)
    }

    @Test
    fun `tools list exposes ask bertbot tool`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { "unused" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val tools = json.getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals("ask_bertbot", tools[0].asJsonObject.get("name").asString)
        assertEquals("bertbot_status", tools[1].asJsonObject.get("name").asString)
    }

    @Test
    fun `tools call routes prompt to bertbot`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { prompt -> "handled: $prompt" })

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"plan this"}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val content = json.getAsJsonObject("result").getAsJsonArray("content")
        assertTrue(content[0].asJsonObject.get("text").asString.contains("handled: plan this"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `tools call returns backend status`() {
        val dispatcher =
            McpRequestDispatcher(
                respondToPrompt = { "unused" },
                statusProvider = { "Connected to bertbot MCP server" },
            )

        val response =
            dispatcher.handle(
                """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"bertbot_status","arguments":{}}}
                """.trimIndent(),
            )

        val json = JsonParser.parseString(response).asJsonObject
        val content = json.getAsJsonObject("result").getAsJsonArray("content")
        assertTrue(content[0].asJsonObject.get("text").asString.contains("Connected to bertbot MCP server"))
        assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `session loop emits responses for incoming requests`() {
        val dispatcher = McpRequestDispatcher(respondToPrompt = { prompt -> "handled: $prompt" })
        val inputs =
            mutableListOf(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """.trimIndent(),
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask_bertbot","arguments":{"prompt":"draft a plan"}}}
                """.trimIndent(),
            )
        val outputs = mutableListOf<String>()

        runMcpSession(
            readLine = { if (inputs.isEmpty()) null else inputs.removeAt(0) },
            writeLine = outputs::add,
            dispatcher = dispatcher,
        )

        assertEquals(2, outputs.size)
        assertTrue(outputs[0].contains("\"protocolVersion\":\"2024-11-05\""))
        assertTrue(outputs[1].contains("handled: draft a plan"))
    }
}
