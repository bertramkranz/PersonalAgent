package com.personalagent.bertbot.app

import com.personalagent.bertbot.graph.model.BertBotState
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptOptimizerTest {
    @Test
    fun `optimizer leaves prompt unchanged when disabled`() {
        val optimizer = PromptOptimizer(PromptOptimizerConfig(enabled = false))
        val result =
            optimizer.optimizePrompt(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Be concise and evidence-backed",
                        state = BertBotState(memorySummary = mutableListOf("User prefers concise answers")),
                        feedbackSignals = listOf("concise", "evidence"),
                    ),
            )

        assertEquals("You are BertBot.", result)
    }

    @Test
    fun `optimizer appends adaptive guidance when enabled and signals are present`() {
        val optimizer = PromptOptimizer(PromptOptimizerConfig(enabled = true, maxInstructionLines = 3))
        val result =
            optimizer.optimizePrompt(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Please be concise and evidence-backed",
                        state = BertBotState(memorySummary = mutableListOf("User prefers concise answers", "User prefers evidence-backed conclusions")),
                        feedbackSignals = listOf("concise", "evidence"),
                    ),
            )

        assertTrue(result.contains("Adaptive instructions"))
        assertTrue(result.contains("be concise"))
        assertTrue(result.contains("evidence-backed"))
    }

    @Test
    fun `optimizer rejects weak proposals and rolls back to base prompt`() {
        val optimizer = PromptOptimizer(PromptOptimizerConfig(enabled = true, proposalConfidenceThreshold = 0.8))
        val proposal =
            optimizer.proposePromptEnhancement(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Be brief",
                        state = BertBotState(memorySummary = mutableListOf("User prefers concise answers")),
                        feedbackSignals = listOf("brief"),
                    ),
            )

        assertFalse(proposal.shouldApply)
        assertEquals("You are BertBot.", optimizer.rollbackPromptEnhancement("You are BertBot.", proposal))
    }

    @Test
    fun `optimizer reinforces repeated proposal patterns over time`() {
        val optimizer = PromptOptimizer(PromptOptimizerConfig(enabled = true, proposalConfidenceThreshold = 0.5))
        val firstProposal =
            optimizer.proposePromptEnhancement(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Please be brief",
                        state = BertBotState(memorySummary = mutableListOf()),
                        feedbackSignals = listOf("brief"),
                    ),
            )
        val secondProposal =
            optimizer.proposePromptEnhancement(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Please be brief",
                        state = BertBotState(memorySummary = mutableListOf()),
                        feedbackSignals = listOf("brief"),
                    ),
            )

        assertFalse(firstProposal.shouldApply)
        assertTrue(secondProposal.shouldApply)
    }

    @Test
    fun `optimizer history persists across optimizer instances`() {
        val tempFile = Files.createTempFile("prompt-optimizer-history", ".json")
        tempFile.toFile().deleteOnExit()

        val firstStore = FileLearningProposalSignalStore(file = tempFile.toFile())
        val firstOptimizer =
            PromptOptimizer(
                PromptOptimizerConfig(enabled = true, proposalConfidenceThreshold = 0.5),
                proposalHistoryStore = LearningProposalSignalStorePromptOptimizerHistoryStore(firstStore),
            )
        val firstProposal =
            firstOptimizer.proposePromptEnhancement(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Please be brief",
                        state = BertBotState(memorySummary = mutableListOf()),
                        feedbackSignals = listOf("brief"),
                    ),
            )

        val secondStore = FileLearningProposalSignalStore(file = tempFile.toFile())
        val secondOptimizer =
            PromptOptimizer(
                PromptOptimizerConfig(enabled = true, proposalConfidenceThreshold = 0.5),
                proposalHistoryStore = LearningProposalSignalStorePromptOptimizerHistoryStore(secondStore),
            )
        val secondProposal =
            secondOptimizer.proposePromptEnhancement(
                basePrompt = "You are BertBot.",
                context =
                    PromptOptimizerContext(
                        userMessage = "Please be brief",
                        state = BertBotState(memorySummary = mutableListOf()),
                        feedbackSignals = listOf("brief"),
                    ),
            )

        assertFalse(firstProposal.shouldApply)
        assertTrue(secondProposal.shouldApply)

        tempFile.deleteIfExists()
    }
}
