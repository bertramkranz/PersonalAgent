package com.personalagent.bertbot.ingestion

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ConsentStore {
    fun load(): List<ApprovalRecord>

    fun upsert(record: ApprovalRecord)

    fun isApproved(source: IngestionSource): Boolean

    fun listApproved(): List<ApprovalRecord>
}

interface SourceStateStore {
    fun load(): List<SyncCursor>

    fun upsert(cursor: SyncCursor)

    fun find(source: IngestionSource): SyncCursor?
}

class FileConsentStore(
    private val storageFile: File = File("bertbot-ingestion-consent.json"),
    private val gson: Gson = Gson(),
) : ConsentStore {
    private var cached: MutableList<ApprovalRecord> = mutableListOf()

    init {
        cached = load().toMutableList()
    }

    override fun load(): List<ApprovalRecord> {
        if (!storageFile.exists()) {
            cached = mutableListOf()
            return cached
        }

        val content = storageFile.readText().trim()
        if (content.isBlank()) {
            cached = mutableListOf()
            return cached
        }

        return try {
            val payload = gson.fromJson(content, PersistedConsentState::class.java)
            val records = payload.toRecords()
            cached = records.toMutableList()
            cached
        } catch (_: JsonSyntaxException) {
            preserveUnreadableStorageFile(storageFile, "consent")
            cached = mutableListOf()
            cached
        }
    }

    override fun upsert(record: ApprovalRecord) {
        val existingIndex = cached.indexOfFirst { it.source.key() == record.source.key() }
        if (existingIndex >= 0) {
            cached[existingIndex] = record
        } else {
            cached.add(record)
        }
        persist()
    }

    override fun isApproved(source: IngestionSource): Boolean {
        val match = cached.lastOrNull { it.source.key() == source.key() } ?: return false
        return match.approved
    }

    override fun listApproved(): List<ApprovalRecord> =
        cached
            .filter { it.approved }
            .sortedBy { it.source.key() }
            .toList()

    private fun persist() {
        writeTextAtomically(storageFile, gson.toJson(PersistedConsentState.fromRecords(cached)))
    }
}

class FileSourceStateStore(
    private val storageFile: File = File("bertbot-ingestion-source-state.json"),
    private val gson: Gson = Gson(),
) : SourceStateStore {
    private var cached: MutableList<SyncCursor> = mutableListOf()

    init {
        cached = load().toMutableList()
    }

    override fun load(): List<SyncCursor> {
        if (!storageFile.exists()) {
            cached = mutableListOf()
            return cached
        }

        val content = storageFile.readText().trim()
        if (content.isBlank()) {
            cached = mutableListOf()
            return cached
        }

        return try {
            val payload = gson.fromJson(content, PersistedSourceState::class.java)
            val cursors = payload.toCursors()
            cached = cursors.toMutableList()
            cached
        } catch (_: JsonSyntaxException) {
            preserveUnreadableStorageFile(storageFile, "source state")
            cached = mutableListOf()
            cached
        }
    }

    override fun upsert(cursor: SyncCursor) {
        val existingIndex = cached.indexOfFirst { it.source.key() == cursor.source.key() }
        if (existingIndex >= 0) {
            cached[existingIndex] = cursor
        } else {
            cached.add(cursor)
        }
        persist()
    }

    override fun find(source: IngestionSource): SyncCursor? =
        cached.lastOrNull { it.source.key() == source.key() }

    private fun persist() {
        writeTextAtomically(storageFile, gson.toJson(PersistedSourceState.fromCursors(cached)))
    }
}

private data class PersistedConsentState(
    val schemaVersion: Int = 1,
    val approvals: List<ApprovalRecord> = emptyList(),
) {
    fun toRecords(): List<ApprovalRecord> {
        if (schemaVersion != 1) {
            return emptyList()
        }
        return approvals
    }

    companion object {
        fun fromRecords(records: List<ApprovalRecord>): PersistedConsentState =
            PersistedConsentState(
                schemaVersion = 1,
                approvals = records,
            )
    }
}

private data class PersistedSourceState(
    val schemaVersion: Int = 1,
    val cursors: List<SyncCursor> = emptyList(),
) {
    fun toCursors(): List<SyncCursor> {
        if (schemaVersion != 1) {
            return emptyList()
        }
        return cursors
    }

    companion object {
        fun fromCursors(cursors: List<SyncCursor>): PersistedSourceState =
            PersistedSourceState(
                schemaVersion = 1,
                cursors = cursors,
            )
    }
}

private fun IngestionSource.key(): String {
    val workspace = workspaceId ?: ""
    return "${platform.name}|${sourceKind.name}|$workspace|$sourceId"
}

private fun writeTextAtomically(
    target: File,
    content: String,
) {
    target.parentFile?.mkdirs()
    val tempFile = File(target.parentFile ?: File("."), "${target.name}.tmp")
    tempFile.writeText(content)
    try {
        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        println("Warning: atomic move unsupported for '${target.path}'. Falling back to non-atomic replace.")
        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun preserveUnreadableStorageFile(
    storageFile: File,
    kind: String,
) {
    val extension = storageFile.extension.takeIf { it.isNotBlank() } ?: "json"
    val backupFile = File(storageFile.parentFile ?: File("."), "${storageFile.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.$extension")
    runCatching {
        Files.copy(storageFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    println("Warning: failed to parse ingestion $kind file '${storageFile.path}'. A backup was preserved at '${backupFile.path}'.")
}
