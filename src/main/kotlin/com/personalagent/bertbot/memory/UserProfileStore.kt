package com.personalagent.bertbot.memory

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class UserProfile(
    val displayName: String? = null,
    val recurringPreferences: Set<String> = emptySet(),
    val communicationStyleHints: Set<String> = emptySet(),
    val stableInterests: Set<String> = emptySet(),
)

class UserProfileStore(
    private val storageFile: File = File("bertbot-profile.json"),
    private val gson: Gson = Gson(),
) {
    private var cached: UserProfile = UserProfile()

    init {
        cached = load()
    }

    fun load(): UserProfile {
        if (!storageFile.exists()) {
            cached = UserProfile()
            return cached
        }

        val content = storageFile.readText().trim()
        if (content.isBlank()) {
            cached = UserProfile()
            return cached
        }

        return try {
            cached = gson.fromJson(content, UserProfile::class.java) ?: UserProfile()
            cached
        } catch (_: JsonSyntaxException) {
            preserveUnreadableStorageFile(storageFile)
            cached = UserProfile()
            cached
        }
    }

    fun current(): UserProfile = cached

    fun updateDisplayName(displayName: String) {
        val normalized = normalizeDisplayName(displayName)
        if (normalized.isBlank()) {
            return
        }

        if (cached.displayName == normalized) {
            return
        }

        cached = cached.copy(displayName = normalized)
        persist()
    }

    fun addRecurringPreference(preference: String) {
        val normalized = normalizeLabel(preference)
        if (normalized.isBlank() || cached.recurringPreferences.contains(normalized)) {
            return
        }

        cached =
            cached.copy(
                recurringPreferences = normalizeSet(cached.recurringPreferences + normalized),
            )
        persist()
    }

    fun addCommunicationStyleHint(hint: String) {
        val normalized = normalizeLabel(hint)
        if (normalized.isBlank() || cached.communicationStyleHints.contains(normalized)) {
            return
        }

        cached =
            cached.copy(
                communicationStyleHints = normalizeSet(cached.communicationStyleHints + normalized),
            )
        persist()
    }

    fun addStableInterest(interest: String) {
        val normalized = normalizeLabel(interest)
        if (normalized.isBlank() || cached.stableInterests.contains(normalized)) {
            return
        }

        cached =
            cached.copy(
                stableInterests = normalizeSet(cached.stableInterests + normalized),
            )
        persist()
    }

    private fun persist() {
        writeTextAtomically(storageFile, gson.toJson(cached))
    }
}

private fun normalizeDisplayName(raw: String): String =
    raw
        .trim()
        .trimEnd('.', '!', '?', ',', ';', ':')
        .replace(Regex("\\s+"), " ")

private fun normalizeLabel(raw: String): String =
    raw
        .trim()
        .trimEnd('.', '!', '?', ',', ';', ':')
        .replace(Regex("\\s+"), " ")

private fun normalizeSet(values: Set<String>): Set<String> =
    values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .takeLast(100)
        .toSet()

private fun preserveUnreadableStorageFile(storageFile: File) {
    val extension = storageFile.extension.takeIf { it.isNotBlank() } ?: "json"
    val backupFile = File(storageFile.parentFile ?: File("."), "${storageFile.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.$extension")
    runCatching {
        Files.copy(storageFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    println("Warning: failed to parse user profile file '${storageFile.path}'. A backup was preserved at '${backupFile.path}'.")
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
