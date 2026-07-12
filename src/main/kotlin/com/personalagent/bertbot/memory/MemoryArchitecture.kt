package com.personalagent.bertbot.memory

import com.personalagent.bertbot.llm.LlmGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File

interface MemoryStore {
    fun append(text: String)

    fun entries(): List<MemoryEntry>

    fun replaceAll(entries: List<MemoryEntry>)

    fun count(): Int = entries().size
}

class EpisodicMemory(
    private val backing: BertBotMemoryStore = createDefaultEpisodicBacking(),
) : MemoryStore {
    override fun append(text: String) {
        backing.remember(text)
    }

    fun append(entry: MemoryEntry) {
        backing.remember(entry)
    }

    override fun entries(): List<MemoryEntry> = backing.entries()

    override fun replaceAll(entries: List<MemoryEntry>) {
        backing.replaceAll(entries)
    }

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = backing.withScope(scopeKey, action)
}

class SemanticMemory(
    private val backing: BertBotMemoryStore = createDefaultSemanticBacking(),
) : MemoryStore {
    override fun append(text: String) {
        backing.remember(text)
    }

    override fun entries(): List<MemoryEntry> = backing.entries()

    override fun replaceAll(entries: List<MemoryEntry>) {
        backing.replaceAll(entries)
    }

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = backing.withScope(scopeKey, action)
}

class DualMemoryContextAssembler(
    private val episodicMemory: MemoryStore,
    private val semanticMemory: MemoryStore,
) {
    fun buildContext(
        maxSemanticEntries: Int = 5,
        maxEpisodicEntries: Int = 10,
    ): List<String> {
        val semanticContext =
            semanticMemory
                .entries()
                .takeLast(maxSemanticEntries)
                .map { entry -> "[semantic] ${entry.text}" }
        val episodicContext =
            episodicMemory
                .entries()
                .takeLast(maxEpisodicEntries)
                .map { entry -> "[episodic] ${entry.text}" }

        return semanticContext + episodicContext
    }
}

interface MemorySummarizer {
    fun summarize(entries: List<MemoryEntry>): String
}

class LlmMemorySummarizer(
    private val llmGateway: LlmGateway,
) : MemorySummarizer {
    override fun summarize(entries: List<MemoryEntry>): String {
        if (entries.isEmpty()) {
            return ""
        }

        val userPrompt =
            entries
                .mapIndexed { index, entry -> "${index + 1}. ${entry.text}" }
                .joinToString(separator = "\n")

        return llmGateway
            .complete(
                systemPrompt =
                    """
                    Summarize memory events into a concise operational summary for an AI assistant.
                    Keep it factual and compact.
                    Return plain text only, no markdown, no JSON.
                    """.trimIndent(),
                userPrompt =
                    """
                    Summarize the following events in 1-3 short sentences:
                    $userPrompt
                    """.trimIndent(),
            ).trim()
    }
}

class SafeMemorySummarizer(
    private val primary: MemorySummarizer,
    private val fallback: MemorySummarizer = RuleBasedMemorySummarizer(),
) : MemorySummarizer {
    override fun summarize(entries: List<MemoryEntry>): String =
        try {
            primary.summarize(entries)
        } catch (_: Exception) {
            fallback.summarize(entries)
        }
}

class RuleBasedMemorySummarizer : MemorySummarizer {
    override fun summarize(entries: List<MemoryEntry>): String {
        val combined = entries.joinToString(separator = " | ") { entry -> entry.text }
        return if (combined.length <= 400) {
            combined
        } else {
            "${combined.take(400)}..."
        }
    }
}

class MemorySummarizationWorker(
    private val episodicMemory: MemoryStore,
    private val semanticMemory: MemoryStore,
    private val summarizer: MemorySummarizer = RuleBasedMemorySummarizer(),
    private val threshold: Int = 15,
    private val summarizeCount: Int = 10,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Closeable {
    private val mutex = Mutex()

    @Volatile
    private var pending = false

    init {
        require(threshold >= 1) { "threshold must be at least 1" }
        require(summarizeCount >= 1) { "summarizeCount must be at least 1" }
        require(summarizeCount <= threshold) { "summarizeCount must be <= threshold" }
    }

    fun scheduleIfNeeded() {
        if (episodicMemory.count() < threshold || pending) {
            return
        }

        pending = true
        scope.launch {
            processNow()
        }
    }

    suspend fun processNow() {
        mutex.withLock {
            try {
                val entries = episodicMemory.entries()
                if (entries.size < threshold) {
                    return
                }

                val toSummarize = entries.take(summarizeCount)
                if (toSummarize.isEmpty()) {
                    return
                }

                val summary = summarizer.summarize(toSummarize)
                semanticMemory.append("Summary: $summary")
                episodicMemory.replaceAll(entries.drop(summarizeCount))
            } finally {
                pending = false
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

private fun createDefaultEpisodicBacking(): BertBotMemoryStore {
    val newPath = File("state/bertbot-memory.txt")
    migrateMemoryFileIfNeeded(newPath, File("bertbot-memory.txt"))
    return BertBotMemory(newPath)
}

private fun createDefaultSemanticBacking(): BertBotMemoryStore {
    val newPath = File("state/bertbot-semantic-memory.txt")
    migrateMemoryFileIfNeeded(newPath, File("bertbot-semantic-memory.txt"))
    return BertBotMemory(newPath)
}

private fun migrateMemoryFileIfNeeded(
    newPath: File,
    legacyPath: File,
) {
    if (!newPath.exists() && legacyPath.exists()) {
        newPath.parentFile?.mkdirs()
        try {
            legacyPath.copyTo(newPath)
            legacyPath.delete()
        } catch (e: Exception) {
            println("Warning: failed to migrate legacy memory file '${legacyPath.path}' to '${newPath.path}': ${e.message}")
        }
    }
}
