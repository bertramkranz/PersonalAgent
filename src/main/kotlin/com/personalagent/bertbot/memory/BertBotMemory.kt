package com.personalagent.bertbot.memory

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter

data class MemoryEntry(
    val text: String,
    val createdAt: String = Instant.now().atZone(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
    val sourceMetadata: MemorySourceMetadata? = null,
    val attachmentReferences: List<MemoryAttachmentReference>? = emptyList(),
)

data class MemorySourceMetadata(
    val platform: String,
    val sourceKind: String,
    val sourceId: String,
    val workspaceId: String? = null,
    val senderId: String? = null,
    val senderDisplayName: String? = null,
    val threadId: String? = null,
    val messageId: String? = null,
)

data class MemoryAttachmentReference(
    val attachmentId: String,
    val kind: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val externalUrl: String? = null,
    val fileReference: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

interface BertBotMemoryStore {
    fun load(): List<MemoryEntry>

    fun remember(text: String)

    fun remember(entry: MemoryEntry)

    fun entries(): List<MemoryEntry>

    fun replaceAll(newEntries: List<MemoryEntry>)

    fun snapshot(): String

    fun count(): Int

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

class BertBotMemory(
    private val storageFile: File = File("state/bertbot-memory.txt"),
    private val gson: Gson = Gson(),
) : BertBotMemoryStore {
    private val lock = Any()
    private val entries = mutableListOf<MemoryEntry>()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private var loadedScopeKey: String? = null

    init {
        load()
    }

    override fun load(): List<MemoryEntry> {
        synchronized(lock) {
            forceReloadForCurrentScope()
            return entries.toList()
        }
    }

    override fun remember(text: String) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            if (text.isBlank()) {
                return
            }

            entries.add(MemoryEntry(text = text.trim()))
            persist()
        }
    }

    override fun remember(entry: MemoryEntry) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            if (entry.text.isBlank()) {
                return
            }

            entries.add(entry.normalized())
            persist()
        }
    }

    override fun entries(): List<MemoryEntry> =
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            entries.toList()
        }

    override fun replaceAll(newEntries: List<MemoryEntry>) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            entries.clear()
            entries.addAll(newEntries.filter { it.text.isNotBlank() }.map { entry -> entry.normalized() })
            persist()
        }
    }

    override fun snapshot(): String =
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            if (entries.isEmpty()) {
                return "No remembered context yet."
            }
            entries.joinToString(separator = System.lineSeparator()) { "- ${it.text}" }
        }

    override fun count(): Int =
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            entries.size
        }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        val previousLegacyAlias = legacyScopeAlias.get()
        currentScope.set(PersistenceScopeKey.normalizeForFile(scopeKey))
        legacyScopeAlias.set(PersistenceScopeKey.legacyFileAlias(scopeKey))
        return try {
            action()
        } finally {
            currentScope.set(previous)
            legacyScopeAlias.set(previousLegacyAlias)
        }
    }

    private fun ensureLoadedForCurrentScope() {
        val scopeKey = currentScope.get()
        if (loadedScopeKey == scopeKey) {
            return
        }
        forceReloadForCurrentScope()
    }

    private fun forceReloadForCurrentScope() {
        entries.clear()
        loadedScopeKey = currentScope.get()
        val file = scopedStorageFile()
        val legacyFile = legacyScopedStorageFile()
        val existingFile = if (file.exists()) file else legacyFile

        if (!existingFile.exists()) {
            return
        }

        if (existingFile == legacyFile && legacyFile != file) {
            println("Warning: memory store loaded legacy scoped file '${legacyFile.path}' because normalized scoped file '${file.path}' was not found.")
        }

        val content = existingFile.readText().trim()
        if (content.isBlank()) {
            return
        }

        // Prefer structured JSON state but gracefully fall back to legacy line-based format.
        val parsedEntries =
            try {
                gson.fromJson(content, Array<MemoryEntry>::class.java)?.toList()
            } catch (_: JsonSyntaxException) {
                null
            }

        if (parsedEntries != null) {
            entries.addAll(parsedEntries.map { it.normalized() })
            return
        }

        if (looksLikeStructuredJson(content)) {
            preserveUnreadableStorageFile(existingFile)
            return
        }

        content.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line -> entries.add(MemoryEntry(text = line.trim())) }
    }

    private fun persist() {
        writeTextAtomically(scopedStorageFile(), gson.toJson(entries))
    }

    private fun scopedStorageFile(): File {
        val scope = currentScope.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return storageFile
        }
        val parent = storageFile.parentFile ?: File(".")
        val stem = storageFile.nameWithoutExtension
        val ext = storageFile.extension.takeIf { it.isNotBlank() } ?: "txt"
        return File(parent, "$stem-$scope.$ext")
    }

    private fun legacyScopedStorageFile(): File {
        val scope = legacyScopeAlias.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return storageFile
        }
        val parent = storageFile.parentFile ?: File(".")
        val stem = storageFile.nameWithoutExtension
        val ext = storageFile.extension.takeIf { it.isNotBlank() } ?: "txt"
        return File(parent, "$stem-$scope.$ext")
    }

    private companion object
}

internal fun MemoryEntry.normalized(): MemoryEntry =
    copy(
        text = text.trim(),
        attachmentReferences = attachmentReferences.orEmpty().filter { it.attachmentId.isNotBlank() },
    )

private fun looksLikeStructuredJson(content: String): Boolean =
    content.startsWith("[") || content.startsWith("{")

private fun preserveUnreadableStorageFile(storageFile: File) {
    val extension = storageFile.extension.takeIf { it.isNotBlank() } ?: "txt"
    val backupFile = File(storageFile.parentFile ?: File("."), "${storageFile.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.$extension")
    runCatching {
        Files.copy(storageFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    println("Warning: failed to parse memory file '${storageFile.path}'. A backup was preserved at '${backupFile.path}'.")
}

private fun writeTextAtomically(
    target: File,
    content: String,
) {
    val parentDir = target.parentFile ?: File(".")
    parentDir.mkdirs()
    val tempPath = Files.createTempFile(parentDir.toPath(), "${target.nameWithoutExtension}-", ".tmp")
    val tempFile = tempPath.toFile()
    try {
        tempFile.writeText(content)
        Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        println("Warning: atomic move unsupported for '${target.path}'. Falling back to non-atomic replace.")
        Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        runCatching { tempFile.delete() }
        throw e
    }
}
