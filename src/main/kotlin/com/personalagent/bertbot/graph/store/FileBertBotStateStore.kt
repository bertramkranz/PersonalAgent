package com.personalagent.bertbot.graph.store

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import java.io.File

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
            BertBotState()
        }
    }

    override fun save(state: BertBotState) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(state))
    }
}
