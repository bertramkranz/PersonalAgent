package com.personalagent.bertbot.llm

enum class ReasoningCapability {
    NONE,
    LIGHTWEIGHT,
    ADVANCED,
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
        ModelCapability(
            modelId = "gpt-4o-mini",
            provider = "openai",
            costPerMillionInputTokens = 0.15,
            costPerMillionOutputTokens = 0.60,
            supportsTools = true,
            maxContextTokens = 128_000,
            reasoningCapability = ReasoningCapability.LIGHTWEIGHT,
        ),
        ModelCapability(
            modelId = "gpt-4o",
            provider = "openai",
            costPerMillionInputTokens = 5.00,
            costPerMillionOutputTokens = 15.00,
            supportsTools = true,
            maxContextTokens = 128_000,
            reasoningCapability = ReasoningCapability.ADVANCED,
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
