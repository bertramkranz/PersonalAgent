package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JdbcBertBotCheckpointStoreTest {
    @Test
    fun `jdbc checkpoint store saves and loads latest per scope`() {
        val jdbcUrl = h2JdbcUrl()
        val store = JdbcBertBotCheckpointStore(jdbcUrl = jdbcUrl)

        store.save(
            BertBotCheckpoint(
                checkpointId = "cp-1",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "planner",
                state = BertBotState(lastUserMessage = "first"),
                createdAtEpochMillis = 1000,
            ),
        )
        store.save(
            BertBotCheckpoint(
                checkpointId = "cp-2",
                scopeKey = "scope-a",
                traceId = "trace-a",
                nodeId = "executor",
                state = BertBotState(lastUserMessage = "second"),
                createdAtEpochMillis = 2000,
            ),
        )

        val latest = store.loadLatest("scope-a")
        val listed = store.list("scope-a")

        assertNotNull(latest)
        assertEquals("cp-2", latest.checkpointId)
        assertEquals("second", latest.state.lastUserMessage)
        assertEquals(listOf("cp-1", "cp-2"), listed.map { it.checkpointId })
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_checkpoint_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
