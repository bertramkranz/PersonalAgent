package com.personalagent.bertbot.agents

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway

internal class ToolCallingSkill(
    private val llmGateway: LlmGateway,
    private val toolDefinitions: List<JsonObject>,
    private val toolExecutor: (name: String, args: JsonObject) -> String,
    private val maxIterations: Int = 5,
) {
    fun invoke(
        systemPrompt: String,
        userPrompt: String,
        tracingContext: TracingContext,
    ): String {
        val augmentedSystemPrompt = buildAugmentedSystemPrompt(systemPrompt)
        val toolResults = mutableListOf<Pair<String, String>>()
        var iteration = 1

        while (iteration <= maxIterations) {
            TraceLogger.skillInvoked(tracingContext, "skill=tool_calling iteration=$iteration")
            val raw =
                llmGateway.complete(augmentedSystemPrompt, buildUserPrompt(userPrompt, toolResults))
            val action = parseActionResponse(raw)

            when {
                action == null -> {
                    TraceLogger.warn(tracingContext, "tool_calling_parse_failed", "iteration=$iteration")
                    return raw.trim()
                }
                action.isRespond -> {
                    TraceLogger.skillCompleted(tracingContext, "skill=tool_calling iterations=$iteration")
                    val response = action.response ?: raw.trim()
                    return formatFinalResponse(response, toolResults)
                }
                action.isCallTool -> {
                    val toolName = action.tool ?: break
                    val args = action.arguments ?: JsonObject()
                    TraceLogger.info(tracingContext, "tool_call", "tool=$toolName iteration=$iteration")
                    val result =
                        runCatching { toolExecutor(toolName, args) }
                            .getOrElse { e -> "Tool error: ${e.message ?: "unknown"}" }
                    TraceLogger.info(
                        tracingContext,
                        "tool_result",
                        "tool=$toolName result_length=${result.length}",
                    )
                    toolResults.add(toolName to result)
                }
                else -> break
            }
            iteration++
        }

        TraceLogger.warn(tracingContext, "tool_calling_max_iterations", "iterations=$maxIterations")
        val finalResponse = llmGateway.complete(systemPrompt, buildUserPrompt(userPrompt, toolResults))
        return formatFinalResponse(finalResponse, toolResults)
    }

    private fun buildAugmentedSystemPrompt(base: String): String {
        if (toolDefinitions.isEmpty()) return base
        val toolList =
            toolDefinitions
                .joinToString(separator = "\n") { tool ->
                    val name = tool.get("name")?.asString ?: return@joinToString ""
                    val desc = tool.get("description")?.asString ?: ""
                    "- $name: $desc"
                }
                .trim()
        return """
            $base

            Available tools:
            $toolList

            IMPORTANT tool usage notes:
            - For Google Calendar operations: use calendarId="primary" to access the user's own calendar.
            - Always include all required arguments for each tool based on its inputSchema.

            To invoke a tool, respond with exactly:
            {"action":"call_tool","tool":"<tool_name>","arguments":{...}}

            To give your final answer, respond with exactly:
            {"action":"respond","response":"<your answer>"}
        """
            .trimIndent()
    }

    private fun buildUserPrompt(
        originalQuestion: String,
        toolResults: List<Pair<String, String>>,
    ): String {
        if (toolResults.isEmpty()) return originalQuestion
        val resultsSection =
            toolResults
                .joinToString(separator = "\n\n") { (name, result) ->
                    "Tool: $name\nResult: $result"
                }
        return """
            $originalQuestion

            Tool results gathered so far:
            $resultsSection

            Using these results, provide your final answer or call another tool if needed.
        """
            .trimIndent()
    }

    private fun formatFinalResponse(
        llmResponse: String,
        toolResults: List<Pair<String, String>>,
    ): String {
        if (toolResults.isEmpty()) return llmResponse

        val formattedParts = mutableListOf<String>()
        for ((_, rawResult) in toolResults) {
            val formatted = formatToolResult(rawResult)
            // Only append if formatting actually changed the result
            if (formatted != rawResult) {
                formattedParts.add(formatted)
            }
        }

        return if (formattedParts.isNotEmpty()) {
            llmResponse + "\n\n" + formattedParts.joinToString("\n\n")
        } else {
            llmResponse
        }
    }

    private fun formatToolResult(raw: String): String {
        return runCatching {
            val json = JsonParser.parseString(raw).asJsonObject
            when {
                json.has("events") -> formatCalendarEvents(json)
                json.has("calendars") -> formatCalendars(json)
                else -> formatGenericJson(json)
            }
        }.getOrElse { raw }
    }

    private fun formatCalendarEvents(json: JsonObject): String {
        val events = json.getAsJsonArray("events")?.mapNotNull { it.asJsonObject } ?: emptyList()
        if (events.isEmpty()) return "No events found."

        return buildString {
            appendLine("📅 Calendar Events:")
            appendLine()
            events.forEach { event ->
                val summary = event.get("summary")?.asString ?: "Untitled"
                val start =
                    event.get("start")?.asJsonObject?.get("dateTime")?.asString
                        ?: event.get("start")?.asJsonObject?.get("date")?.asString
                        ?: "Unknown time"
                val end =
                    event.get("end")?.asJsonObject?.get("dateTime")?.asString
                        ?: event.get("end")?.asJsonObject?.get("date")?.asString
                        ?: ""
                val description =
                    event.get("description")?.asString?.take(100)?.let { " - $it" } ?: ""

                appendLine("• $summary")
                appendLine("  Start: $start")
                if (end.isNotBlank()) appendLine("  End: $end")
                if (description.isNotBlank()) appendLine("  Details:$description")
                appendLine()
            }
        }
    }

    private fun formatCalendars(json: JsonObject): String {
        val calendars = json.getAsJsonArray("calendars")?.mapNotNull { it.asJsonObject } ?: emptyList()
        if (calendars.isEmpty()) return "No calendars found."

        return buildString {
            appendLine("📋 Your Calendars:")
            appendLine()
            calendars.forEach { cal ->
                val id = cal.get("id")?.asString ?: "unknown"
                val summary = cal.get("summary")?.asString ?: "Unnamed"
                val isPrimary = cal.get("primary")?.asBoolean ?: false
                val badge = if (isPrimary) " (Primary)" else ""
                appendLine("• $summary$badge")
                if (id != "unknown") appendLine("  ID: $id")
                appendLine()
            }
        }
    }

    private fun formatGenericJson(json: JsonObject): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(json)
    }

    private fun parseActionResponse(raw: String): ToolActionResponse? {
        val normalized =
            raw
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        return runCatching {
            val json = JsonParser.parseString(normalized).asJsonObject
            when (json.get("action")?.asString) {
                "respond" ->
                    ToolActionResponse(
                        isRespond = true,
                        response = json.get("response")?.asString?.trim(),
                    )
                "call_tool" ->
                    ToolActionResponse(
                        isCallTool = true,
                        tool = json.get("tool")?.asString,
                        arguments = json.getAsJsonObject("arguments"),
                    )
                else -> null
            }
        }.getOrNull()
    }
}

private data class ToolActionResponse(
    val isRespond: Boolean = false,
    val isCallTool: Boolean = false,
    val response: String? = null,
    val tool: String? = null,
    val arguments: JsonObject? = null,
)
