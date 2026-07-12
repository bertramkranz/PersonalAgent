package com.personalagent.bertbot.app

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.rag.base.storage.search.KeywordSearchRequest
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.utils.time.KoogClock
import com.personalagent.bertbot.memory.BertBotMemory
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.SemanticMemory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogMemoryAdaptersTest {
    @Test
    fun `chat history provider stores and loads conversation using scoped memory`() =
        runBlocking {
            val file = Files.createTempFile("koog-chat-memory", ".json").toFile()
            val episodicMemory = EpisodicMemory(BertBotMemory(file))
            val provider = KoogScopedChatHistoryProvider(episodicMemory, windowSize = 2)

            provider.store(
                conversationId = "thread-1",
                messages =
                    listOf(
                        Message.User("first", RequestMetaInfo.create(KoogClock.System)),
                        Message.Assistant("second", ResponseMetaInfo.create(KoogClock.System)),
                        Message.User("third", RequestMetaInfo.create(KoogClock.System)),
                    ),
            )

            val loaded = provider.load("thread-1")

            assertEquals(2, loaded.size)
            assertTrue(loaded[0] is Message.Assistant)
            assertTrue(loaded[1] is Message.User)
        }

    @Test
    fun `long term storage indexes and searches by keyword and similarity`() =
        runBlocking {
            val file = Files.createTempFile("koog-ltm-memory", ".json").toFile()
            val semanticMemory = SemanticMemory(BertBotMemory(file))
            val storage = KoogScopedLongTermStorage(semanticMemory = semanticMemory, defaultTopK = 5)

            storage.add(
                namespace = "session-1",
                documents =
                    listOf(
                        ai.koog.agents.longtermmemory.model.MemoryRecord(
                            content = "Kotlin coroutines simplify async code",
                            id = "rec-1",
                        ),
                        ai.koog.agents.longtermmemory.model.MemoryRecord(
                            content = "Bananas are yellow",
                            id = "rec-2",
                        ),
                    ),
            )

            val keywordResults = storage.search(KeywordSearchRequest(queryText = "kotlin"), namespace = "session-1")
            val similarityResults =
                storage.search(
                    SimilaritySearchRequest(queryText = "async kotlin code", limit = 1),
                    namespace = "session-1",
                )

            assertEquals(1, keywordResults.size)
            assertEquals("rec-1", keywordResults.first().document.id)
            assertEquals(1, similarityResults.size)
            assertEquals("rec-1", similarityResults.first().document.id)
        }
}
