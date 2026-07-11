package com.personalagent.bertbot.memory

import com.personalagent.bertbot.llm.LlmGateway
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
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

    @Test
    fun `memory loader preserves unreadable structured json before resetting entries`() {
        val tempDirectory = createTempDirectory(prefix = "bertbot-memory").toFile()
        tempDirectory.deleteOnExit()
        val storageFile = File(tempDirectory, "bertbot-memory.txt")
        storageFile.writeText("[not-valid-json")

        val memory = BertBotMemory(storageFile)

        assertTrue(memory.entries().isEmpty())
        val backups = tempDirectory.listFiles { _, name -> name.startsWith("bertbot-memory.corrupt-") }
        assertTrue(backups?.isNotEmpty() == true)
    }

    @Test
    fun `memory store keeps all entries under concurrent writes`() {
        val file = File.createTempFile("bertbot-memory", ".json")
        file.delete()
        file.deleteOnExit()

        val memory = BertBotMemory(file)
        val workerCount = 6
        val writesPerWorker = 40
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)
        val expected = mutableSetOf<String>()

        try {
            repeat(workerCount) { worker ->
                repeat(writesPerWorker) { index ->
                    expected.add("event-$worker-$index")
                }
            }

            repeat(workerCount) { worker ->
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(writesPerWorker) { index ->
                            memory.remember("event-$worker-$index")
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        val reloaded = BertBotMemory(file)
        val texts = reloaded.entries().map { it.text }.toSet()

        assertEquals(expected.size, reloaded.count())
        assertEquals(expected, texts)
        assertTrue(file.readText().trim().startsWith("["))
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
