package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.MemorySummarizationWorker
import com.personalagent.bertbot.memory.SemanticMemory
import com.personalagent.bertbot.memory.UserProfileStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BertBotRuntimeHelpersTest {
    @Test
    fun `extractDisplayNameFromMessage captures explicit name`() {
        assertEquals("Bertram Kranz", extractDisplayNameFromMessage("My name is Bertram Kranz."))
    }

    @Test
    fun `extractDisplayNameFromMessage returns null when no explicit name`() {
        assertEquals(null, extractDisplayNameFromMessage("I am doing well today"))
    }

    @Test
    fun `isNameRecallQuestion detects common name lookup prompts`() {
        assertTrue(isNameRecallQuestion("What is my name?"))
        assertTrue(isNameRecallQuestion("Do you know my name"))
        assertFalse(isNameRecallQuestion("What is my task"))
    }

    @Test
    fun `request context builder includes profile summary and prefixed trace id`() {
        val profileFile = File.createTempFile("bertbot-profile", ".json")
        profileFile.delete()
        profileFile.deleteOnExit()
        val episodicMemory = EpisodicMemory()
        val semanticMemory = SemanticMemory()
        val userProfileStore = UserProfileStore(profileFile)
        userProfileStore.updateDisplayName("Bertram Kranz")
        val memoryRuntime =
            BertBotMemoryRuntime(
                episodicMemory = episodicMemory,
                memoryAssembler = DualMemoryContextAssembler(episodicMemory, semanticMemory),
                memoryWorker = MemorySummarizationWorker(episodicMemory, semanticMemory, threshold = 10, summarizeCount = 5),
                userProfileStore = userProfileStore,
            )
        val builder = BertBotRequestContextBuilder(BertBotAgentConfig(), memoryRuntime)

        try {
            val context = builder.build("please review architecture", traceCorrelationId = "corr")

            assertEquals("please review architecture", context.initialState.lastUserMessage)
            assertTrue(context.initialState.profileSummary.contains("Known user name: Bertram Kranz"))
            assertTrue(context.requestTraceId.startsWith("mcp-corr-"))
            assertTrue(context.initialState.memorySummary.any { it.contains("USER: please review architecture") })
        } finally {
            memoryRuntime.memoryWorker.close()
        }
    }
}
