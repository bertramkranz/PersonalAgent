package com.personalagent.bertbot.memory

import com.personalagent.bertbot.llm.LlmGateway
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryArchitectureTest {
    @Test
    fun `assembler combines semantic and episodic context`() {
        val episodicFile = File.createTempFile("episodic-memory", ".json")
        val semanticFile = File.createTempFile("semantic-memory", ".json")
        episodicFile.deleteOnExit()
        semanticFile.deleteOnExit()

        val episodicMemory = EpisodicMemory(BertBotMemory(episodicFile))
        val semanticMemory = SemanticMemory(BertBotMemory(semanticFile))
        episodicMemory.append("USER: hello")
        episodicMemory.append("ASSISTANT: hi")
        semanticMemory.append("Summary: user greets assistant")

        val assembler = DualMemoryContextAssembler(episodicMemory, semanticMemory)
        val context = assembler.buildContext(maxSemanticEntries = 3, maxEpisodicEntries = 3)

        assertEquals(3, context.size)
        assertTrue(context[0].startsWith("[semantic]"))
        assertTrue(context[1].startsWith("[episodic]"))
    }

    @Test
    fun `worker summarizes oldest episodic entries into semantic memory`() {
        val episodicFile = File.createTempFile("episodic-memory", ".json")
        val semanticFile = File.createTempFile("semantic-memory", ".json")
        episodicFile.deleteOnExit()
        semanticFile.deleteOnExit()

        val episodicMemory = EpisodicMemory(BertBotMemory(episodicFile))
        val semanticMemory = SemanticMemory(BertBotMemory(semanticFile))

        repeat(15) { index ->
            episodicMemory.append("event-$index")
        }

        val worker =
            MemorySummarizationWorker(
                episodicMemory = episodicMemory,
                semanticMemory = semanticMemory,
                threshold = 15,
                summarizeCount = 10,
            )

        runBlocking {
            worker.processNow()
        }

        assertEquals(5, episodicMemory.count())
        assertEquals(1, semanticMemory.count())
        assertTrue(semanticMemory.entries().first().text.contains("event-0"))

        worker.close()
    }

    @Test
    fun `llm summarizer uses gateway output`() {
        val gateway = FakeLlmGateway(response = "User asked for urgent follow-up and assistant confirmed completion.")
        val summarizer = LlmMemorySummarizer(gateway)

        val summary =
            summarizer.summarize(
                listOf(
                    MemoryEntry("USER: please prioritize email triage"),
                    MemoryEntry("ASSISTANT: I prioritized urgent items and delegated routine follow-up"),
                ),
            )

        assertEquals("User asked for urgent follow-up and assistant confirmed completion.", summary)
        assertTrue(gateway.calls == 1)
    }

    @Test
    fun `safe summarizer falls back when primary fails`() {
        val primary =
            object : MemorySummarizer {
                override fun summarize(entries: List<MemoryEntry>): String {
                    error("primary failed")
                }
            }
        val fallback = RuleBasedMemorySummarizer()
        val summarizer = SafeMemorySummarizer(primary = primary, fallback = fallback)

        val summary =
            summarizer.summarize(
                listOf(
                    MemoryEntry("first"),
                    MemoryEntry("second"),
                ),
            )

        assertTrue(summary.contains("first"))
        assertTrue(summary.contains("second"))
    }
}

private class FakeLlmGateway(
    private val response: String,
) : LlmGateway {
    var calls: Int = 0

    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        calls += 1
        return response
    }
}
