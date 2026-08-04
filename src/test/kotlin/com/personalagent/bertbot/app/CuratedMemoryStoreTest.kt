package com.personalagent.bertbot.app

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CuratedMemoryStoreTest {
    @Test
    fun `file curated memory store trims per scope and preserves pinned items when possible`() {
        val file = File.createTempFile("bertbot-curated-memory", ".json")
        file.delete()
        file.deleteOnExit()

        val store = FileCuratedMemoryStore(file = file, maxEntriesPerScope = 3)

        store.withScope("scope-a") {
            store.add(
                CuratedMemoryCreateRequest(
                    category = "facts",
                    content = "a-1",
                    source = "test",
                    pinned = true,
                ),
            )
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "a-2", source = "test"))
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "a-3", source = "test"))
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "a-4", source = "test"))
        }

        store.withScope("scope-b") {
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "b-1", source = "test"))
        }

        val scopeA = store.withScope("scope-a") { store.list(limit = 10) }
        val scopeB = store.withScope("scope-b") { store.list(limit = 10) }

        assertEquals(3, scopeA.size)
        assertTrue(scopeA.any { it.content == "a-1" && it.pinned })
        assertEquals(listOf("b-1"), scopeB.map { it.content })
    }

    @Test
    fun `file curated memory store supports update remove and clear in scope`() {
        val file = File.createTempFile("bertbot-curated-memory-mutate", ".json")
        file.delete()
        file.deleteOnExit()

        val store = FileCuratedMemoryStore(file = file, maxEntriesPerScope = 10)

        val created =
            store.withScope("scope-a") {
                store.add(CuratedMemoryCreateRequest(category = "todo", content = "item", source = "test"))
            }

        val updated =
            store.withScope("scope-a") {
                store.update(
                    id = created.id,
                    request = CuratedMemoryUpdateRequest(content = "item-updated", confidence = 0.9, pinned = true),
                )
            }

        assertNotNull(updated)
        assertEquals("item-updated", updated.content)
        assertEquals(0.9, updated.confidence)
        assertEquals(true, updated.pinned)

        val removed = store.withScope("scope-a") { store.remove(created.id) }
        assertTrue(removed)
        assertNull(store.withScope("scope-a") { store.get(created.id) })

        store.withScope("scope-a") {
            store.add(CuratedMemoryCreateRequest(category = "todo", content = "item-2", source = "test"))
            store.clear()
        }
        assertTrue(store.withScope("scope-a") { store.list(limit = 10).isEmpty() })
    }

    @Test
    fun `jdbc curated memory store trims and isolates scopes`() {
        val store =
            JdbcCuratedMemoryStore(
                jdbcUrl = h2JdbcUrl(),
                tableName = "bertbot_curated_memory_snapshot",
                maxEntriesPerScope = 2,
            )

        store.withScope("scope-a") {
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "a-1", source = "test", pinned = true))
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "a-2", source = "test"))
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "a-3", source = "test"))
        }

        store.withScope("scope-b") {
            store.add(CuratedMemoryCreateRequest(category = "facts", content = "b-1", source = "test"))
        }

        val scopeA = store.withScope("scope-a") { store.list(limit = 10) }
        val scopeB = store.withScope("scope-b") { store.list(limit = 10) }

        assertEquals(2, scopeA.size)
        assertTrue(scopeA.any { it.content == "a-1" && it.pinned })
        assertEquals(listOf("b-1"), scopeB.map { it.content })

        val removedId = scopeA.first().id
        val removed = store.withScope("scope-a") { store.remove(removedId) }
        assertTrue(removed)
        assertFalse(store.withScope("scope-a") { store.list(limit = 10).map { it.id }.contains(removedId) })
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_curated_memory_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
