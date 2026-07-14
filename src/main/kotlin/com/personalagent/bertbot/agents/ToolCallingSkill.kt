package com.personalagent.bertbot.agents

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class ToolCallingSkill(
    private val llmGateway: LlmGateway,
    private val toolDefinitionsProvider: () -> List<JsonObject>,
    private val toolExecutor: (name: String, args: JsonObject) -> String,
    private val maxIterations: Int = 5,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
    private val structuredOutputGateway: StructuredOutputGateway = JsonStructuredOutputGateway(),
) {
    constructor(
        llmGateway: LlmGateway,
        toolDefinitions: List<JsonObject>,
        toolExecutor: (name: String, args: JsonObject) -> String,
        maxIterations: Int = 5,
        codec: AgentJsonCodec = GsonAgentJsonCodec(),
        structuredOutputGateway: StructuredOutputGateway = JsonStructuredOutputGateway(),
    ) : this(
        llmGateway = llmGateway,
        toolDefinitionsProvider = { toolDefinitions },
        toolExecutor = toolExecutor,
        maxIterations = maxIterations,
        codec = codec,
        structuredOutputGateway = structuredOutputGateway,
    )

    fun invoke(
        systemPrompt: String,
        userPrompt: String,
        tracingContext: TracingContext,
    ): String {
        val augmentedSystemPrompt = buildAugmentedSystemPrompt(systemPrompt, toolDefinitionsProvider())
        val toolResults = mutableListOf<Pair<String, String>>()
        var iteration = 1

        while (iteration <= maxIterations) {
            TraceLogger.skillInvoked(tracingContext, "skill=tool_calling iteration=$iteration")
            val raw =
                llmGateway.complete(augmentedSystemPrompt, buildUserPrompt(userPrompt, toolResults))
            val action =
                parseActionResponse(raw)
                    ?: recoverActionResponse(
                        augmentedSystemPrompt = augmentedSystemPrompt,
                        userPrompt = userPrompt,
                        toolResults = toolResults,
                    )

            when {
                action == null -> {
                    TraceLogger.warn(tracingContext, "tool_calling_parse_failed", "iteration=$iteration")
                    return forceFinalResponse(
                        augmentedSystemPrompt = augmentedSystemPrompt,
                        userPrompt = userPrompt,
                        toolResults = toolResults,
                    )
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
                        "tool=$toolName result_length=${result.length} preview=${sanitizeResultPreview(result)}",
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

    private fun recoverActionResponse(
        augmentedSystemPrompt: String,
        userPrompt: String,
        toolResults: List<Pair<String, String>>,
    ): ToolAction? {
        val recoveryPrompt =
            buildString {
                appendLine(buildUserPrompt(userPrompt, toolResults))
                appendLine()
                appendLine("Your previous reply was invalid.")
                appendLine("Return ONLY valid JSON using one of these exact shapes:")
                appendLine("{\"action\":\"call_tool\",\"tool\":\"<tool_name>\",\"arguments\":{...}}")
                appendLine("{\"action\":\"respond\",\"response\":\"<your answer>\"}")
                appendLine("Do NOT return delegate/broadcast/internal orchestration actions.")
            }

        val retryRaw = llmGateway.complete(augmentedSystemPrompt, recoveryPrompt)
        return parseActionResponse(retryRaw)
    }

    private fun forceFinalResponse(
        augmentedSystemPrompt: String,
        userPrompt: String,
        toolResults: List<Pair<String, String>>,
    ): String {
        val forcedPrompt =
            buildString {
                appendLine(buildUserPrompt(userPrompt, toolResults))
                appendLine()
                appendLine("Your previous replies did not follow the required action JSON schema.")
                appendLine("Now provide ONLY the final user-facing answer in plain text.")
                appendLine("Do not output JSON.")
                appendLine("Do not mention internal delegation, sub-agents, or background execution.")
            }
        return llmGateway.complete(augmentedSystemPrompt, forcedPrompt).trim()
    }

    private fun sanitizeResultPreview(result: String): String {
        val collapsed = result.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val redacted =
            collapsed
                .replace(Regex("(?i)(access_token|refresh_token)\\\"?\\s*[:=]\\s*\\\"[^\\\"]+\\\""), "$1=\"[REDACTED]\"")
                .replace(Regex("(?i)(access_token|refresh_token)\\s*[:=]\\s*[^,\\s]+"), "$1=[REDACTED]")
        return redacted.take(420)
    }

    private fun buildAugmentedSystemPrompt(
        base: String,
        toolDefinitions: List<JsonObject>,
    ): String {
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
            val unwrapped = unwrapToolResult(json)
            if (unwrapped != null) {
                return runCatching {
                    val unwrappedJson = JsonParser.parseString(unwrapped).asJsonObject
                    when {
                        unwrappedJson.has("events") -> formatCalendarEvents(unwrappedJson)
                        unwrappedJson.has("calendars") -> formatCalendars(unwrappedJson)
                        else -> formatGenericJson(unwrappedJson)
                    }
                }.getOrElse { unwrapped }
            }
            when {
                json.has("events") -> formatCalendarEvents(json)
                json.has("calendars") -> formatCalendars(json)
                else -> formatGenericJson(json)
            }
        }.getOrElse { raw }
    }

    private fun unwrapToolResult(json: JsonObject): String? {
        val content = json.getAsJsonArray("content") ?: return null
        val firstText =
            content
                .firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("text")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
                ?: return null
        return firstText.takeIf { it.isNotBlank() }
    }

    private fun formatCalendarEvents(json: JsonObject): String {
        val events = json.getAsJsonArray("events")?.mapNotNull { it.asJsonObject } ?: emptyList()
        if (events.isEmpty()) return "No events found."

        return buildString {
            appendLine("📅 Calendar Events (${events.size}):")
            appendLine()
            events.forEachIndexed { index, event ->
                val summary = event.get("summary")?.asString ?: "Untitled"
                val start =
                    event.get("start")?.asJsonObject?.get("dateTime")?.asString
                        ?: event.get("start")?.asJsonObject?.get("date")?.asString
                        ?: "Unknown time"
                val end =
                    event.get("end")?.asJsonObject?.get("dateTime")?.asString
                        ?: event.get("end")?.asJsonObject?.get("date")?.asString
                        ?: ""
                val description = event.get("description")?.asString?.trim().orEmpty()
                val location = event.get("location")?.asString?.trim().orEmpty()

                appendLine("${index + 1}. $summary")
                appendLine("   When: ${prettyDateTime(start)}")
                if (end.isNotBlank()) appendLine("   Ends: ${prettyDateTime(end)}")
                if (location.isNotBlank()) appendLine("   Where: ${location.take(120)}")
                if (description.isNotBlank()) appendLine("   Details: ${description.take(160)}")
                appendLine()
            }
        }
    }

    private fun formatCalendars(json: JsonObject): String {
        val calendars = json.getAsJsonArray("calendars")?.mapNotNull { it.asJsonObject } ?: emptyList()
        if (calendars.isEmpty()) return "No calendars found."

        return buildString {
            appendLine("📋 Your Calendars (${calendars.size}):")
            appendLine()
            calendars.forEachIndexed { index, cal ->
                val id = cal.get("id")?.asString ?: "unknown"
                val summary = cal.get("summary")?.asString ?: "Unnamed"
                val isPrimary = cal.get("primary")?.asBoolean ?: false
                val badge = if (isPrimary) " (Primary)" else ""
                appendLine("${index + 1}. $summary$badge")
                if (id != "unknown") appendLine("   ID: $id")
                appendLine()
            }
        }
    }

    private fun prettyDateTime(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "Unknown time"

        val parsedInstant =
            runCatching { Instant.parse(trimmed) }
                .map { instant ->
                    DateTimeFormatter
                        .ofPattern("EEE, MMM d yyyy HH:mm 'UTC'")
                        .withZone(ZoneOffset.UTC)
                        .format(instant)
                }.getOrNull()
        if (parsedInstant != null) return parsedInstant

        val parsedDate =
            runCatching { LocalDate.parse(trimmed) }
                .map { date ->
                    DateTimeFormatter
                        .ofPattern("EEE, MMM d yyyy")
                        .format(date)
                }.getOrNull()
        if (parsedDate != null) return "$parsedDate (all day)"

        return trimmed
    }

    private fun formatGenericJson(json: JsonObject): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(json)
    }

    private fun parseActionResponse(raw: String): ToolAction? {
        val normalized = extractJsonObjectCandidate(raw)
        val payload =
            runCatching {
                codec.decode(structuredOutputGateway.parse(normalized).toString(), ToolActionEnvelope::class.java)
            }.getOrNull() ?: return null

        return runCatching {
            when (payload.action) {
                "respond" ->
                    ToolAction(
                        isRespond = true,
                        response = payload.response?.trim(),
                    )
                "call_tool" ->
                    ToolAction(
                        isCallTool = true,
                        tool = payload.tool,
                        arguments = payload.arguments ?: JsonObject(),
                    )
                else -> null
            }
        }.getOrNull()
    }

    private fun extractJsonObjectCandidate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        return findFirstJsonObject(trimmed) ?: trimmed
    }

    private fun findFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) {
            return null
        }

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) {
                continue
            }
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }
}

private data class ToolAction(
    val isRespond: Boolean = false,
    val isCallTool: Boolean = false,
    val response: String? = null,
    val tool: String? = null,
    val arguments: JsonObject? = null,
)

private data class ToolActionEnvelope(
    val action: String? = null,
    val response: String? = null,
    val tool: String? = null,
    val arguments: JsonObject? = null,
)
