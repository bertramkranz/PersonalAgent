package com.personalagent.bertbot.memory

import java.io.File
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
}
