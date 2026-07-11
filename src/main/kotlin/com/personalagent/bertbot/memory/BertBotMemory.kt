package com.personalagent.bertbot.memory

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

data class MemoryEntry(
    val text: String,
    val createdAt: String = Instant.now().atZone(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
)

class BertBotMemory(
    private val storageFile: File = File("bertbot-memory.txt"),
    private val gson: Gson = Gson(),
) {
    private val entries = mutableListOf<MemoryEntry>()

    init {
        load()
    }

    fun load(): List<MemoryEntry> {
        if (!storageFile.exists()) {
            return emptyList()
        }

        entries.clear()
        val content = storageFile.readText().trim()
        if (content.isBlank()) {
            return emptyList()
        }

        // Prefer structured JSON state but gracefully fall back to legacy line-based format.
        val parsedEntries =
            try {
                gson.fromJson(content, Array<MemoryEntry>::class.java)?.toList()
            } catch (_: JsonSyntaxException) {
                null
            }

        if (parsedEntries != null) {
            entries.addAll(parsedEntries)
            return entries.toList()
        }

        content.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line -> entries.add(MemoryEntry(text = line.trim())) }

        return entries.toList()
    }

    fun remember(text: String) {
        if (text.isBlank()) {
            return
        }

        entries.add(MemoryEntry(text = text.trim()))
        persist()
    }

    fun remember(entry: MemoryEntry) {
        if (entry.text.isBlank()) {
            return
        }

        entries.add(entry.copy(text = entry.text.trim()))
        persist()
    }

    fun entries(): List<MemoryEntry> = entries.toList()

    fun replaceAll(newEntries: List<MemoryEntry>) {
        entries.clear()
        entries.addAll(newEntries.filter { it.text.isNotBlank() }.map { entry -> entry.copy(text = entry.text.trim()) })
        persist()
    }

    fun snapshot(): String {
        if (entries.isEmpty()) {
            return "No remembered context yet."
        }

        return entries.joinToString(separator = System.lineSeparator()) { "- ${it.text}" }
    }

    fun count(): Int = entries.size

    private fun persist() {
        storageFile.writeText(gson.toJson(entries))
    }
}
