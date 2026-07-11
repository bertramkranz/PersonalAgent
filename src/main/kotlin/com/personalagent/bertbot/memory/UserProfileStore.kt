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

class UserProfileStore internal constructor(
    private val persistence: UserProfilePersistence,
) {
    constructor(
        storageFile: File = File("bertbot-profile.json"),
        gson: Gson = Gson(),
    ) : this(FileUserProfileStore(storageFile, gson))

    fun load(): UserProfile = persistence.load()

    fun current(): UserProfile = persistence.current()

    fun updateDisplayName(displayName: String) = persistence.updateDisplayName(displayName)

    fun addRecurringPreference(preference: String) = persistence.addRecurringPreference(preference)

    fun addCommunicationStyleHint(hint: String) = persistence.addCommunicationStyleHint(hint)

    fun addStableInterest(interest: String) = persistence.addStableInterest(interest)

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = persistence.withScope(scopeKey, action)
}

private class FileUserProfileStore(
    private val storageFile: File,
    private val gson: Gson,
) : UserProfilePersistence {
    private val lock = Any()
    private var cached: UserProfile = UserProfile()

    init {
        cached = load()
    }

    override fun load(): UserProfile {
        synchronized(lock) {
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
    }

    override fun current(): UserProfile = synchronized(lock) { cached }

    override fun updateDisplayName(displayName: String) {
        synchronized(lock) {
            val normalized = normalizeDisplayName(displayName)
            if (normalized.isBlank() || cached.displayName == normalized) {
                return
            }

            cached = cached.copy(displayName = normalized)
            persist()
        }
    }

    override fun addRecurringPreference(preference: String) {
        synchronized(lock) {
            val normalized = normalizeLabel(preference)
            if (normalized.isBlank() || cached.recurringPreferences.contains(normalized)) {
                return
            }

            cached = cached.copy(recurringPreferences = normalizeSet(cached.recurringPreferences + normalized))
            persist()
        }
    }

    override fun addCommunicationStyleHint(hint: String) {
        synchronized(lock) {
            val normalized = normalizeLabel(hint)
            if (normalized.isBlank() || cached.communicationStyleHints.contains(normalized)) {
                return
            }

            cached = cached.copy(communicationStyleHints = normalizeSet(cached.communicationStyleHints + normalized))
            persist()
        }
    }

    override fun addStableInterest(interest: String) {
        synchronized(lock) {
            val normalized = normalizeLabel(interest)
            if (normalized.isBlank() || cached.stableInterests.contains(normalized)) {
                return
            }

            cached = cached.copy(stableInterests = normalizeSet(cached.stableInterests + normalized))
            persist()
        }
    }

    private fun persist() {
        writeTextAtomically(storageFile, gson.toJson(cached))
    }
}

internal fun normalizeDisplayName(raw: String): String =
    raw
        .trim()
        .trimEnd('.', '!', '?', ',', ';', ':')
        .replace(Regex("\\s+"), " ")

internal fun normalizeLabel(raw: String): String =
    raw
        .trim()
        .trimEnd('.', '!', '?', ',', ';', ':')
        .replace(Regex("\\s+"), " ")

internal fun normalizeSet(values: Set<String>): Set<String> =
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
    val copied =
        runCatching {
            Files.copy(storageFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    if (copied) {
        println("Warning: failed to parse user profile file '${storageFile.path}'. A backup was preserved at '${backupFile.path}'.")
    } else {
        println("Warning: failed to parse user profile file '${storageFile.path}'. Backup could not be created.")
    }
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
        try {
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            println("Warning: atomic move unsupported for '${target.path}'. Falling back to non-atomic replace.")
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        runCatching { tempFile.delete() }
        throw e
    }
}
