package com.personalagent.bertbot.agents

import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway

data class SelfCorrectingSkillRequest(
    val systemPrompt: String,
    val userPrompt: String,
)

class SelfCorrectingSkill<O>(
    private val name: String,
    private val llmGateway: LlmGateway,
    private val outputFormatInstructions: String,
    private val parser: (String) -> O,
    private val maxAttempts: Int = 3,
) : Skill<SelfCorrectingSkillRequest, O> {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    override fun invoke(
        input: SelfCorrectingSkillRequest,
        tracingContext: TracingContext,
    ): O {
        var attempt = 1
        var currentUserPrompt = input.userPrompt
        var lastError: Throwable? = null
        var rawOutput = ""

        while (attempt <= maxAttempts) {
            TraceLogger.skillInvoked(tracingContext, "skill=$name attempt=$attempt")

            rawOutput =
                llmGateway.complete(
                    systemPrompt = buildSystemPrompt(input.systemPrompt),
                    userPrompt = currentUserPrompt,
                )

            try {
                val parsed = parser(rawOutput)
                TraceLogger.skillCompleted(tracingContext, "skill=$name attempt=$attempt")
                return parsed
            } catch (e: Exception) {
                lastError = e
                if (attempt == maxAttempts) {
                    break
                }

                TraceLogger.warn(
                    tracingContext,
                    "skill_parse_failed",
                    "skill=$name attempt=$attempt error=${e.message ?: "unknown"}",
                )
                currentUserPrompt = buildCorrectionPrompt(input.userPrompt, rawOutput, e)
                attempt += 1
            }
        }

        throw SelfCorrectionFailedException(
            skillName = name,
            attempts = maxAttempts,
            lastOutput = rawOutput,
            cause = lastError,
        )
    }

    private fun buildSystemPrompt(baseSystemPrompt: String): String =
        """
        $baseSystemPrompt

        Output contract:
        $outputFormatInstructions
        """.trimIndent()

    private fun buildCorrectionPrompt(
        originalPrompt: String,
        previousOutput: String,
        error: Throwable,
    ): String =
        """
        Your previous output did not match the required format.

        Original user prompt:
        $originalPrompt

        Previous output:
        $previousOutput

        Parse error:
        ${error.message ?: "unknown parse error"}

        Rewrite the response to satisfy the output contract exactly.
        """.trimIndent()
}

class SelfCorrectionFailedException(
    skillName: String,
    attempts: Int,
    lastOutput: String,
    cause: Throwable? = null,
) : RuntimeException(
        "Self-correcting skill '$skillName' failed after $attempts attempts. Last output: $lastOutput",
        cause,
    )
