package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.StateOnlyRollbackService
import com.personalagent.bertbot.graph.runtime.StateReplayService
import com.personalagent.bertbot.graph.store.FileBertBotCheckpointStore
import com.personalagent.bertbot.graph.store.FileBertBotStateStore
import com.personalagent.bertbot.graph.store.FileStateEventStore
import com.personalagent.bertbot.llm.LlmGateway
import com.personalagent.bertbot.memory.BertBotMemory
import com.personalagent.bertbot.memory.DualMemoryContextAssembler
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.MemorySummarizationWorker
import com.personalagent.bertbot.memory.SemanticMemory
import com.personalagent.bertbot.memory.UserProfileStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BertBotRuntimeCheckpointTest {
    @Test
    @Suppress("LongMethod")
    fun `runtime lists and rolls back checkpoints per normalized external scope`() {
        val stateFile = File.createTempFile("bertbot-state", ".json")
        val checkpointFile = File.createTempFile("bertbot-checkpoints", ".json")
        val episodicFile = File.createTempFile("bertbot-episodic", ".json")
        val semanticFile = File.createTempFile("bertbot-semantic", ".json")
        val profileFile = File.createTempFile("bertbot-profile", ".json")
        stateFile.delete()
        checkpointFile.delete()
        episodicFile.delete()
        semanticFile.delete()
        profileFile.delete()
        stateFile.deleteOnExit()
        checkpointFile.deleteOnExit()
        episodicFile.deleteOnExit()
        semanticFile.deleteOnExit()
        profileFile.deleteOnExit()

        val stateStore = FileBertBotStateStore(stateFile)
        val checkpointStore = FileBertBotCheckpointStore(checkpointFile)
        val stateEventFile = File.createTempFile("bertbot-state-events", ".json")
        stateEventFile.delete()
        stateEventFile.deleteOnExit()
        val stateEventStore = FileStateEventStore(stateEventFile)
        val graph =
            BertBotGraphRunner(
                definition =
                    BertBotGraphDefinition(
                        entryNodeId = NodeIds.CAPTURE,
                        nodes = listOf(MessageCaptureNode()),
                        edges = emptyList(),
                    ),
                stateStore = stateStore,
                checkpointStore = checkpointStore,
                enableAutomaticCheckpointing = true,
                eventSourcing = BertBotGraphRunner.EventSourcingConfiguration(enabled = true, store = stateEventStore),
            )

        val episodicMemory = EpisodicMemory(BertBotMemory(episodicFile))
        val semanticMemory = SemanticMemory(BertBotMemory(semanticFile))
        val memoryRuntime =
            BertBotMemoryRuntime(
                episodicMemory = episodicMemory,
                memoryAssembler = DualMemoryContextAssembler(episodicMemory, semanticMemory),
                memoryWorker = MemorySummarizationWorker(episodicMemory, semanticMemory, threshold = 10, summarizeCount = 5),
                userProfileStore = UserProfileStore(profileFile),
            )
        val runtime =
            BertBotRuntime(
                config = BertBotAgentConfig(),
                aiRuntimeConfiguration = AiRuntimeConfiguration(provider = "openai", model = "gpt-4o-mini", apiKey = "test-key"),
                stateStore = stateStore,
                graph = graph,
                assistantResponseSkill = createAssistantResponseSkill(StaticGateway()),
                memoryRuntime = memoryRuntime,
                checkpointStore = checkpointStore,
                rollbackService = StateOnlyRollbackService(stateStore, checkpointStore),
                stateEventStore = stateEventStore,
                stateReplayService = StateReplayService(checkpointStore, stateEventStore),
            )

        try {
            val scopeA = "external|telegram|chat|a|root"
            val scopeB = "external|telegram|chat|b|root"
            runtime.respondTo("message-a", persistenceScopeKey = scopeA)
            runtime.respondTo("message-b", persistenceScopeKey = scopeB)

            val checkpointsA = runtime.listCheckpoints(scopeA)
            val checkpointsB = runtime.listCheckpoints(scopeB)

            assertEquals(1, checkpointsA.size)
            assertEquals(1, checkpointsB.size)
            assertEquals("message-a", checkpointsA.single().state.lastUserMessage)
            assertEquals("message-b", checkpointsB.single().state.lastUserMessage)

            val latestA = runtime.latestCheckpoint(scopeA)
            assertNotNull(latestA)
            val byIdA = runtime.checkpointById(latestA.checkpointId, scopeA)
            assertNotNull(byIdA)
            assertEquals(latestA.checkpointId, byIdA.checkpointId)

            val scopeAEvents = runtime.listStateEvents(scopeA)
            assertTrue(scopeAEvents.isNotEmpty())

            val replayed = runtime.replayStateToCheckpoint(latestA.checkpointId, scopeA)
            assertEquals("message-a", replayed.lastUserMessage)

            stateStore.withScope(normalize(scopeA)) {
                stateStore.save(com.personalagent.bertbot.graph.model.BertBotState(lastUserMessage = "mutated"))
            }

            val restored = runtime.rollbackToLatest(scopeA)
            assertEquals("message-a", restored.lastUserMessage)

            val loadedAfterRollback = stateStore.withScope(normalize(scopeA)) { stateStore.load() }
            assertEquals("message-a", loadedAfterRollback.lastUserMessage)
            assertTrue(runtime.listCheckpoints(scopeA).isNotEmpty())
        } finally {
            runtime.close()
        }
    }

    private fun normalize(scope: String): String = scope.replace("|", "_")
}

private class StaticGateway : LlmGateway {
    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String = "{\"response\":\"ok\"}"
}
