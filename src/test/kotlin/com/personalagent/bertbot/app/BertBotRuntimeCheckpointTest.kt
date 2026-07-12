package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.nodes.MessageCaptureNode
import com.personalagent.bertbot.graph.nodes.NodeIds
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.BertBotGraphDefinition
import com.personalagent.bertbot.graph.runtime.BertBotGraphRunner
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventStore
import com.personalagent.bertbot.graph.runtime.StateOnlyRollbackService
import com.personalagent.bertbot.graph.runtime.StateReplayService
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
        val episodicFile = File.createTempFile("bertbot-episodic", ".json")
        val semanticFile = File.createTempFile("bertbot-semantic", ".json")
        val profileFile = File.createTempFile("bertbot-profile", ".json")
        episodicFile.delete()
        semanticFile.delete()
        profileFile.delete()
        episodicFile.deleteOnExit()
        semanticFile.deleteOnExit()
        profileFile.deleteOnExit()

        val stateStore = ScopedInMemoryBertBotStateStore()
        val checkpointStore = InMemoryCheckpointStore()
        val stateEventStore = InMemoryStateEventStore()
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

private class InMemoryCheckpointStore : BertBotCheckpointStore {
    private val checkpoints = mutableListOf<BertBotCheckpoint>()

    override fun save(checkpoint: BertBotCheckpoint) {
        checkpoints.removeIf { it.scopeKey == checkpoint.scopeKey && it.checkpointId == checkpoint.checkpointId }
        checkpoints.add(checkpoint)
    }

    override fun loadLatest(scopeKey: String): BertBotCheckpoint? =
        checkpoints
            .filter { it.scopeKey == scopeKey }
            .maxByOrNull { it.createdAtEpochMillis }

    override fun loadById(
        scopeKey: String,
        checkpointId: String,
    ): BertBotCheckpoint? =
        checkpoints.firstOrNull { it.scopeKey == scopeKey && it.checkpointId == checkpointId }

    override fun list(scopeKey: String): List<BertBotCheckpoint> =
        checkpoints
            .filter { it.scopeKey == scopeKey }
            .sortedBy { it.createdAtEpochMillis }
}

private class InMemoryStateEventStore : StateEventStore {
    private val events = mutableListOf<StateEvent>()

    override fun append(event: StateEvent) {
        events.add(event)
    }

    override fun list(scopeKey: String): List<StateEvent> =
        events
            .filter { it.scopeKey == scopeKey }
            .sortedBy { it.createdAtEpochMillis }
}

private class ScopedInMemoryBertBotStateStore : BertBotStateStore {
    private val statesByScope = mutableMapOf<String, com.personalagent.bertbot.graph.model.BertBotState>()
    private val currentScope = ThreadLocal.withInitial { DEFAULT_SCOPE }

    override fun load(): com.personalagent.bertbot.graph.model.BertBotState =
        statesByScope[currentScope.get()]?.copy() ?: com.personalagent.bertbot.graph.model.BertBotState()

    override fun save(state: com.personalagent.bertbot.graph.model.BertBotState) {
        statesByScope[currentScope.get()] = state.copy()
    }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        currentScope.set(scopeKey)
        return try {
            action()
        } finally {
            currentScope.set(previous)
        }
    }

    private companion object {
        private const val DEFAULT_SCOPE = "global"
    }
}
