package com.personalagent.bertbot.graph.store

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileBertBotStateStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : BertBotStateStore {
    override fun load(): BertBotState {
        if (!file.exists()) {
            return BertBotState()
        }

        val content = file.readText()
        if (content.isBlank()) {
            return BertBotState()
        }

        return try {
            gson.fromJson(content, BertBotState::class.java) ?: BertBotState()
        } catch (_: JsonSyntaxException) {
            preserveUnreadableFile(file, "state")
            BertBotState()
        }
    }

    override fun save(state: BertBotState) {
        file.parentFile?.mkdirs()
        writeTextAtomically(file, gson.toJson(state))
    }
}

private fun writeTextAtomically(
    target: File,
    content: String,
) {
    target.parentFile?.mkdirs()
    val tempFile = File(target.parentFile ?: File("."), "${target.name}.tmp")
    tempFile.writeText(content)
    Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private fun preserveUnreadableFile(
    file: File,
    kind: String,
) {
    val extension = file.extension.takeIf { it.isNotBlank() } ?: "txt"
    val backupFile = File(file.parentFile ?: File("."), "${file.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.$extension")
    runCatching {
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    println("Warning: failed to parse persisted $kind file '${file.path}'. A backup was preserved at '${backupFile.path}'.")
}
