package com.personalagent.bertbot.agents

import com.google.gson.JsonElement
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.GatewayResolution
import com.personalagent.bertbot.llm.LlmGateway

data class SelfCorrectingSkillRequest(
    val systemPrompt: String,
    val userPrompt: String,
)

data class SelfCorrectingSkillConfig<O>(
    val name: String,
    val llmGateway: LlmGateway,
    val outputFormatInstructions: String,
    val parser: (JsonElement) -> O,
    val maxAttempts: Int = 3,
    val structuredOutputGateway: StructuredOutputGateway = JsonStructuredOutputGateway(),
    val gatewayResolver: ((String?) -> GatewayResolution)? = null,
)

class SelfCorrectingSkill<O>(
    private val config: SelfCorrectingSkillConfig<O>,
) : Skill<SelfCorrectingSkillRequest, O> {
    @Suppress("LongParameterList")
    constructor(
        name: String,
        llmGateway: LlmGateway,
        outputFormatInstructions: String,
        parser: (JsonElement) -> O,
        maxAttempts: Int = 3,
        structuredOutputGateway: StructuredOutputGateway = JsonStructuredOutputGateway(),
        gatewayResolver: ((String?) -> GatewayResolution)? = null,
    ) : this(
        config =
            SelfCorrectingSkillConfig(
                name = name,
                llmGateway = llmGateway,
                outputFormatInstructions = outputFormatInstructions,
                parser = parser,
                maxAttempts = maxAttempts,
                structuredOutputGateway = structuredOutputGateway,
                gatewayResolver = gatewayResolver,
            ),
    )

    init {
        require(config.maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    override fun invoke(
        input: SelfCorrectingSkillRequest,
        tracingContext: TracingContext,
    ): O {
        return invoke(input, tracingContext, selectedModelId = null)
    }

    fun invoke(
        input: SelfCorrectingSkillRequest,
        tracingContext: TracingContext,
        selectedModelId: String? = null,
    ): O {
        var attempt = 1
        var currentUserPrompt = input.userPrompt
        var lastError: Throwable? = null
        var rawOutput = ""
        val resolution = resolveGateway(selectedModelId)
        val activeGateway = resolution.gateway

        TraceLogger.info(
            tracingContext,
            "llm_model_resolution",
            "requested_model=${resolution.requestedModelId ?: "default"} effective_model=${resolution.effectiveModelId} fallback_reason=${resolution.fallbackReason ?: "none"}",
        )

        while (attempt <= config.maxAttempts) {
            TraceLogger.skillInvoked(tracingContext, "skill=${config.name} attempt=$attempt")

            rawOutput =
                activeGateway.complete(
                    systemPrompt = buildSystemPrompt(input.systemPrompt),
                    userPrompt = currentUserPrompt,
                )

            try {
                val parsed = config.parser(parseStructuredOutput(rawOutput))
                TraceLogger.skillCompleted(tracingContext, "skill=${config.name} attempt=$attempt")
                return parsed
            } catch (e: Exception) {
                lastError = e
                if (attempt == config.maxAttempts) {
                    break
                }

                TraceLogger.warn(
                    tracingContext,
                    "skill_parse_failed",
                    "skill=${config.name} attempt=$attempt error=${e.message ?: "unknown"}",
                )
                currentUserPrompt = buildCorrectionPrompt(input.userPrompt, rawOutput, e)
                attempt += 1
            }
        }

        throw SelfCorrectionFailedException(
            skillName = config.name,
            attempts = config.maxAttempts,
            lastOutput = rawOutput,
            cause = lastError,
        )
    }

    private fun resolveGateway(selectedModelId: String?): GatewayResolution =
        config.gatewayResolver?.invoke(selectedModelId)
            ?: GatewayResolution(
                gateway = config.llmGateway,
                requestedModelId = selectedModelId,
                effectiveModelId = selectedModelId?.takeIf { it.isNotBlank() } ?: "default",
            )

    private fun parseStructuredOutput(rawOutput: String): JsonElement {
        return config.structuredOutputGateway.parse(rawOutput)
    }

    private fun buildSystemPrompt(baseSystemPrompt: String): String =
        """
        $baseSystemPrompt

        Output contract:
        ${config.outputFormatInstructions}
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
