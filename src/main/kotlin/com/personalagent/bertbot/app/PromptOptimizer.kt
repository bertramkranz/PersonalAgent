package com.personalagent.bertbot.app

import com.personalagent.bertbot.graph.model.BertBotState
import kotlin.math.min

internal interface PromptOptimizerHistoryStore {
    fun increment(signalSignature: String): Int
}

internal class InMemoryPromptOptimizerHistoryStore : PromptOptimizerHistoryStore {
    private val proposalHistory = mutableMapOf<String, Int>()

    override fun increment(signalSignature: String): Int {
        val previousCount = proposalHistory.getOrDefault(signalSignature, 0)
        proposalHistory[signalSignature] = previousCount + 1
        return previousCount
    }
}

internal class LearningProposalSignalStorePromptOptimizerHistoryStore(
    private val signalStore: LearningProposalSignalStore,
) : PromptOptimizerHistoryStore {
    override fun increment(signalSignature: String): Int {
        val normalizedSignature = signalSignature.trim().lowercase().ifBlank { "optimizer:unknown" }
        val persistedState = signalStore.upsert(dedupeKey = normalizedSignature, incrementBy = 1)
        return persistedState.count - 1
    }
}

internal data class PromptOptimizerConfig(
    val enabled: Boolean = false,
    val maxInstructionLines: Int = 4,
    val proposalConfidenceThreshold: Double = 0.75,
)

internal data class PromptOptimizerContext(
    val userMessage: String = "",
    val state: BertBotState = BertBotState(),
    val feedbackSignals: List<String> = emptyList(),
)

internal data class PromptOptimizerProposal(
    val shouldApply: Boolean,
    val enhancedPrompt: String,
    val confidence: Double,
    val reason: String,
)

internal class PromptOptimizer(
    private val config: PromptOptimizerConfig = PromptOptimizerConfig(),
    private val proposalHistoryStore: PromptOptimizerHistoryStore = InMemoryPromptOptimizerHistoryStore(),
) {
    fun optimizePrompt(
        basePrompt: String,
        context: PromptOptimizerContext,
    ): String {
        val proposal = proposePromptEnhancement(basePrompt, context)
        return if (proposal.shouldApply) {
            proposal.enhancedPrompt
        } else {
            rollbackPromptEnhancement(basePrompt, proposal)
        }
    }

    fun proposePromptEnhancement(
        basePrompt: String,
        context: PromptOptimizerContext,
    ): PromptOptimizerProposal {
        if (!config.enabled) {
            return PromptOptimizerProposal(
                shouldApply = false,
                enhancedPrompt = basePrompt,
                confidence = 0.0,
                reason = "optimizer disabled",
            )
        }

        val signalTokens = extractSignalTokens(context)
        val memoryHints = extractMemoryHints(context)

        if (signalTokens.isEmpty() && memoryHints.isEmpty()) {
            return PromptOptimizerProposal(
                shouldApply = false,
                enhancedPrompt = basePrompt,
                confidence = 0.0,
                reason = "no adaptive signals available",
            )
        }

        val signalSignature =
            (signalTokens + memoryHints).joinToString(separator = "::")
        val repeatedCount = proposalHistoryStore.increment(signalSignature)
        val confidence = calculateConfidence(signalTokens, memoryHints, context.userMessage, repeatedCount)
        val adaptiveLines = buildAdaptiveLines(signalTokens, memoryHints, context.userMessage)
        val enhancedPrompt =
            listOf(basePrompt, adaptiveLines.joinToString(separator = "\n")).joinToString(separator = "\n\n")

        val shouldApply = confidence >= config.proposalConfidenceThreshold
        return PromptOptimizerProposal(
            shouldApply = shouldApply,
            enhancedPrompt = if (shouldApply) enhancedPrompt else basePrompt,
            confidence = confidence,
            reason = if (shouldApply) "confidence threshold met" else "confidence below threshold",
        )
    }

    fun rollbackPromptEnhancement(
        basePrompt: String,
        @Suppress("unused") proposal: PromptOptimizerProposal,
    ): String = basePrompt

    private fun extractSignalTokens(context: PromptOptimizerContext): List<String> =
        context.feedbackSignals
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(config.maxInstructionLines)
            .toList()

    private fun extractMemoryHints(context: PromptOptimizerContext): List<String> =
        context.state.memorySummary
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .toList()

    private fun calculateConfidence(
        signalTokens: List<String>,
        memoryHints: List<String>,
        userMessage: String,
        repeatedCount: Int,
    ): Double {
        var confidence = 0.0
        if (signalTokens.isNotEmpty()) {
            confidence += 0.3
        }
        if (memoryHints.isNotEmpty()) {
            confidence += 0.2
        }
        if (userMessage.isNotBlank()) {
            confidence += 0.1
        }
        if (signalTokens.size > 1) {
            confidence += 0.2
        }
        if (memoryHints.size > 1) {
            confidence += 0.1
        }
        if (repeatedCount > 0) {
            confidence += 0.1 * min(repeatedCount, 3)
        }
        return min(confidence, 1.0)
    }

    private fun buildAdaptiveLines(
        signalTokens: List<String>,
        memoryHints: List<String>,
        userMessage: String,
    ): List<String> =
        buildList {
            add("Adaptive instructions")
            if (signalTokens.isNotEmpty()) {
                add("- Follow the user’s recent preferences: ${signalTokens.joinToString(", ")}")
            }
            if (memoryHints.isNotEmpty()) {
                add("- Use remembered context: ${memoryHints.joinToString("; ")}")
            }
            if (userMessage.isNotBlank()) {
                add("- Continue adapting to the current request: ${userMessage.trim()}")
            }
        }.let { lines ->
            val contentLines = lines.drop(1)
            val limitedContentLines = contentLines.take(config.maxInstructionLines)
            listOf(lines.first()) + limitedContentLines
        }
}
