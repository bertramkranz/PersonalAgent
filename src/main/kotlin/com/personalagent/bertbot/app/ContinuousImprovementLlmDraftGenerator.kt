package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway

internal data class ResearchDraftCandidate(
    val key: String,
    val title: String,
    val category: String,
    val rationale: String,
    val evidence: List<String>,
    val impact: Int,
    val effort: Int,
    val confidence: Int,
)

internal object ResearchLlmDraftGenerator {
    fun generate(
        llmGateway: LlmGateway,
        workspaceSnapshot: String,
        tracingContext: TracingContext,
    ): List<ResearchDraftCandidate> {
        val rawOutput =
            runCatching {
                llmGateway.complete(
                    systemPrompt = buildLlmResearchSystemPrompt(),
                    userPrompt = buildLlmResearchUserPrompt(workspaceSnapshot),
                )
            }.getOrElse { error ->
                TraceLogger.warn(
                    tracingContext,
                    "research_llm_failed",
                    "message=${error.message ?: "unknown"}",
                )
                return emptyList()
            }

        return parseDraftCandidates(rawOutput, tracingContext)
    }

    private fun parseDraftCandidates(
        rawOutput: String,
        tracingContext: TracingContext,
    ): List<ResearchDraftCandidate> {
        val normalized =
            rawOutput
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        return runCatching {
            val root = JsonParser.parseString(normalized).asJsonObject
            val rawRecommendations = root.getAsJsonArray("recommendations") ?: return emptyList()
            rawRecommendations
                .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
                .mapNotNull { json -> recommendationDraftFromJson(json) }
                .take(5)
                .toList()
        }.onFailure { error ->
            TraceLogger.warn(
                tracingContext,
                "research_llm_parse_failed",
                "message=${error.message ?: "unknown"}",
            )
        }.getOrDefault(emptyList())
    }

    private fun recommendationDraftFromJson(json: JsonObject): ResearchDraftCandidate? {
        val key = json.get("key")?.asString?.trim().orEmpty()
        val title = json.get("title")?.asString?.trim().orEmpty()
        val category = json.get("category")?.asString?.trim()?.lowercase().orEmpty()
        val rationale = json.get("rationale")?.asString?.trim().orEmpty()
        val evidence =
            json.getAsJsonArray("evidence")
                ?.mapNotNull { item -> runCatching { item.asString.trim() }.getOrNull() }
                ?.filter { value -> value.isNotBlank() }
                ?.take(5)
                ?: emptyList()

        val isValid =
            key.isNotBlank() &&
                title.isNotBlank() &&
                category in allowedCategories &&
                rationale.isNotBlank()
        if (!isValid) {
            return null
        }

        val impact = json.get("impact")?.asInt ?: 3
        val effort = json.get("effort")?.asInt ?: 3
        val confidence = json.get("confidence")?.asInt ?: 3

        return ResearchDraftCandidate(
            key = key,
            title = title,
            category = category,
            rationale = rationale,
            evidence = evidence,
            impact = impact,
            effort = effort,
            confidence = confidence,
        )
    }

    private fun buildLlmResearchSystemPrompt(): String =
        """
        You are a repository-improvement analyst for a Kotlin/MCP/AI assistant project.
        Return ONLY JSON with this schema:
        {
          "recommendations": [
            {
              "key": "string",
              "title": "string",
              "category": "kotlin|mcp|ai|security|productivity|performance",
              "rationale": "string",
              "evidence": ["string"],
              "impact": 1-5,
              "effort": 1-5,
              "confidence": 1-5
            }
          ]
        }
        Constraints:
        - Use at most 5 recommendations.
        - Keep titles concise and action-oriented.
        - Ground evidence in provided repository snapshot lines only.
        - Do not include markdown or additional commentary.
        """.trimIndent()

    private fun buildLlmResearchUserPrompt(workspaceSnapshot: String): String =
        """
        Generate prioritized repository improvement recommendations.

        Repository snapshot:
        $workspaceSnapshot
        """.trimIndent()

    private val allowedCategories = setOf("kotlin", "mcp", "ai", "security", "productivity", "performance")
}
