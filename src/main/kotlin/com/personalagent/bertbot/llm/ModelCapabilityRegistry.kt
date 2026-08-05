package com.personalagent.bertbot.llm

enum class ReasoningCapability {
    NONE,
    LIGHTWEIGHT,
    ADVANCED,
    EXPERT,
}

data class ModelCapability(
    val modelId: String,
    val provider: String,
    val costPerMillionInputTokens: Double,
    val costPerMillionOutputTokens: Double,
    val supportsTools: Boolean,
    val maxContextTokens: Int,
    val reasoningCapability: ReasoningCapability,
)

class ModelCapabilityRegistry(
    models: List<ModelCapability> = defaultModelCapabilities(),
) {
    private val modelsById: Map<String, ModelCapability> = models.associateBy { it.modelId }

    fun modelById(id: String): ModelCapability? = modelsById[id]

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) {
            return 0
        }
        // Conservative heuristic: ~4 chars per token for mixed prose/code.
        return (text.length / 4.0).toInt().coerceAtLeast(1)
    }

    fun estimateCost(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
    ): Double {
        val model = modelById(modelId) ?: return 0.0
        return (inputTokens / 1_000_000.0) * model.costPerMillionInputTokens +
            (outputTokens / 1_000_000.0) * model.costPerMillionOutputTokens
    }
}

fun defaultModelCapabilities(): List<ModelCapability> =
    listOf(
        // Current frontier — gpt-5.6 family
        ModelCapability(
            modelId = "gpt-5.6-luna",
            provider = "openai",
            costPerMillionInputTokens = 0.20,
            costPerMillionOutputTokens = 1.20,
            supportsTools = true,
            maxContextTokens = 1_047_576,
            reasoningCapability = ReasoningCapability.LIGHTWEIGHT,
        ),
        ModelCapability(
            modelId = "gpt-5.6-terra",
            provider = "openai",
            costPerMillionInputTokens = 2.00,
            costPerMillionOutputTokens = 12.00,
            supportsTools = true,
            maxContextTokens = 1_047_576,
            reasoningCapability = ReasoningCapability.ADVANCED,
        ),
        ModelCapability(
            modelId = "gpt-5.6-sol",
            provider = "openai",
            costPerMillionInputTokens = 5.00,
            costPerMillionOutputTokens = 30.00,
            supportsTools = true,
            maxContextTokens = 1_047_576,
            reasoningCapability = ReasoningCapability.EXPERT,
        ),
        // Strongest mini for coding, computer use, and subagents
        ModelCapability(
            modelId = "gpt-5.4-mini",
            provider = "openai",
            costPerMillionInputTokens = 0.75,
            costPerMillionOutputTokens = 4.50,
            supportsTools = true,
            maxContextTokens = 1_047_576,
            reasoningCapability = ReasoningCapability.LIGHTWEIGHT,
        ),
        // Previous generation — retained for backward compatibility
        ModelCapability(
            modelId = "gpt-4.1-mini",
            provider = "openai",
            costPerMillionInputTokens = 0.40,
            costPerMillionOutputTokens = 1.60,
            supportsTools = true,
            maxContextTokens = 1_047_576,
            reasoningCapability = ReasoningCapability.LIGHTWEIGHT,
        ),
        ModelCapability(
            modelId = "gpt-4.1",
            provider = "openai",
            costPerMillionInputTokens = 2.00,
            costPerMillionOutputTokens = 8.00,
            supportsTools = true,
            maxContextTokens = 1_047_576,
            reasoningCapability = ReasoningCapability.ADVANCED,
        ),
        ModelCapability(
            modelId = "o3",
            provider = "openai",
            costPerMillionInputTokens = 2.00,
            costPerMillionOutputTokens = 8.00,
            supportsTools = true,
            maxContextTokens = 200_000,
            reasoningCapability = ReasoningCapability.EXPERT,
        ),
        ModelCapability(
            modelId = "llama3.1",
            provider = "ollama",
            costPerMillionInputTokens = 0.0,
            costPerMillionOutputTokens = 0.0,
            supportsTools = false,
            maxContextTokens = 8_192,
            reasoningCapability = ReasoningCapability.LIGHTWEIGHT,
        ),
    )
