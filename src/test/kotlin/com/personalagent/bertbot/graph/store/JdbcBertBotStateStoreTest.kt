package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcBertBotStateStoreTest {
    @Test
    fun `jdbc store persists and reloads schema versioned snapshot`() {
        val jdbcUrl = h2JdbcUrl()
        val store = JdbcBertBotStateStore(jdbcUrl = jdbcUrl)

        store.save(
            BertBotState(
                lastUserMessage = "review architecture",
                pendingTasks = mutableListOf("task-1"),
            ),
        )

        val loaded = store.load()

        assertEquals("review architecture", loaded.lastUserMessage)
        assertEquals(listOf("task-1"), loaded.pendingTasks)
        val persistedPayload = readPayload(jdbcUrl)
        assertTrue(persistedPayload.contains("\"schemaVersion\":2"))
    }

    @Test
    fun `jdbc store supports legacy state payload parsing`() {
        val jdbcUrl = h2JdbcUrl()
        createLegacyTable(jdbcUrl, tableName = "bertbot_state_legacy")
        val store = JdbcBertBotStateStore(jdbcUrl = jdbcUrl, tableName = "bertbot_state_legacy")
        writePayload(
            jdbcUrl = jdbcUrl,
            tableName = "bertbot_state_legacy",
            payload = """{"lastUserMessage":"legacy message","pendingTasks":["legacy task"]}""",
        )

        val loaded = store.load()

        assertEquals("legacy message", loaded.lastUserMessage)
        assertEquals(listOf("legacy task"), loaded.pendingTasks)
    }

    @Test
    fun `jdbc store isolates snapshots per scope`() {
        val jdbcUrl = h2JdbcUrl()
        val store = JdbcBertBotStateStore(jdbcUrl = jdbcUrl)

        store.withScope("scope-a") {
            store.save(BertBotState(lastUserMessage = "from-a"))
        }
        store.withScope("scope-b") {
            store.save(BertBotState(lastUserMessage = "from-b"))
        }

        val loadedA = store.withScope("scope-a") { store.load() }
        val loadedB = store.withScope("scope-b") { store.load() }

        assertEquals("from-a", loadedA.lastUserMessage)
        assertEquals("from-b", loadedB.lastUserMessage)
    }

    private fun writePayload(
        jdbcUrl: String,
        tableName: String = "bertbot_state_snapshot",
        payload: String,
    ) {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            val updated = updatePayload(connection = connection, tableName = tableName, payload = payload)
            if (updated == 0) {
                insertPayload(connection = connection, tableName = tableName, payload = payload)
            }
        }
    }

    private fun updatePayload(
        connection: java.sql.Connection,
        tableName: String,
        payload: String,
    ): Int {
        val sql = "UPDATE $tableName SET payload = ?, updated_at = CURRENT_TIMESTAMP WHERE snapshot_id = 1"
        connection.prepareStatement(sql).use { update ->
            update.setString(1, payload)
            return update.executeUpdate()
        }
    }

    private fun insertPayload(
        connection: java.sql.Connection,
        tableName: String,
        payload: String,
    ) {
        val sql = "INSERT INTO $tableName (snapshot_id, payload) VALUES (1, ?)"
        connection.prepareStatement(sql).use { insert ->
            insert.setString(1, payload)
            insert.executeUpdate()
        }
    }

    private fun createLegacyTable(
        jdbcUrl: String,
        tableName: String,
    ) {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        snapshot_id INTEGER PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun readPayload(jdbcUrl: String): String {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement("SELECT payload FROM bertbot_state_snapshot WHERE scope_key = 'global'").use { statement ->
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "missing persisted snapshot row" }
                    return resultSet.getString("payload")
                }
            }
        }
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_state_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
