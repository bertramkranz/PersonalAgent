package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.model.BertBotState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileBertBotStateStoreTest {
    @Test
    fun `save overwrites prior snapshot with latest state`() {
        val file = File.createTempFile("bertbot-state", ".json")
        file.delete()
        file.deleteOnExit()
        val store = FileBertBotStateStore(file)

        store.save(BertBotState(lastUserMessage = "first", pendingTasks = mutableListOf("first-task")))
        store.save(BertBotState(lastUserMessage = "second", pendingTasks = mutableListOf("second-task")))

        val reloaded = store.load()

        assertEquals("second", reloaded.lastUserMessage)
        assertEquals(listOf("second-task"), reloaded.pendingTasks)
    }

    @Test
    fun `concurrent saves preserve a readable versioned snapshot`() {
        val file = File.createTempFile("bertbot-state", ".json")
        file.delete()
        file.deleteOnExit()
        val store = FileBertBotStateStore(file)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(4)
        val executor = Executors.newFixedThreadPool(4)
        val expectedTasksByMessage =
            mapOf(
                "message-1" to "task-1",
                "message-2" to "task-2",
                "message-3" to "task-3",
                "message-4" to "task-4",
            )

        try {
            expectedTasksByMessage.forEach { (message, task) ->
                executor.submit {
                    try {
                        startLatch.await()
                        store.save(
                            BertBotState(
                                lastUserMessage = message,
                                pendingTasks = mutableListOf(task),
                            ),
                        )
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        val reloaded = store.load()
        val storedContent = file.readText()

        assertTrue(storedContent.contains("\"schemaVersion\":2"))
        assertTrue(reloaded.lastUserMessage in expectedTasksByMessage.keys)
        assertEquals(
            expectedTasksByMessage.getValue(reloaded.lastUserMessage),
            reloaded.pendingTasks.single(),
        )
    }
}
