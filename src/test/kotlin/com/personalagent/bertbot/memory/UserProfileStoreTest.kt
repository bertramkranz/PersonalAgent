package com.personalagent.bertbot.memory

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserProfileStoreTest {
    @Test
    fun `store persists display name`() {
        val file = File.createTempFile("bertbot-profile", ".json")
        file.delete()
        file.deleteOnExit()

        val store = UserProfileStore(file)
        store.updateDisplayName("Bertram Kranz")

        val reloaded = UserProfileStore(file)
        assertEquals("Bertram Kranz", reloaded.current().displayName)
    }

    @Test
    fun `store preserves unreadable profile file and resets state`() {
        val file = File.createTempFile("bertbot-profile", ".json")
        file.deleteOnExit()
        file.writeText("{not-valid-json")

        val store = UserProfileStore(file)

        assertEquals(null, store.current().displayName)
        val backups = file.parentFile?.listFiles { _, name -> name.startsWith("${file.nameWithoutExtension}.corrupt-") }
        assertTrue(backups?.isNotEmpty() == true)
    }

    @Test
    fun `store persists conservative learning fields`() {
        val file = File.createTempFile("bertbot-profile", ".json")
        file.delete()
        file.deleteOnExit()

        val store = UserProfileStore(file)
        store.addRecurringPreference("concise updates")
        store.addCommunicationStyleHint("prefers step-by-step explanations")
        store.addStableInterest("kotlin")

        val reloaded = UserProfileStore(file)
        assertTrue(reloaded.current().recurringPreferences.contains("concise updates"))
        assertTrue(reloaded.current().communicationStyleHints.contains("prefers step-by-step explanations"))
        assertTrue(reloaded.current().stableInterests.contains("kotlin"))
    }

    @Test
    fun `store preserves all stable interests under concurrent updates`() {
        val file = File.createTempFile("bertbot-profile", ".json")
        file.delete()
        file.deleteOnExit()

        val store = UserProfileStore(file)
        val workerCount = 5
        val writesPerWorker = 20
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            repeat(workerCount) { worker ->
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(writesPerWorker) { index ->
                            store.addStableInterest("interest-$worker-$index")
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        val reloaded = UserProfileStore(file)
        val interests = reloaded.current().stableInterests

        assertEquals(workerCount * writesPerWorker, interests.size)
        assertTrue(interests.contains("interest-0-0"))
        assertTrue(file.readText().contains("\"stableInterests\""))
    }
}
