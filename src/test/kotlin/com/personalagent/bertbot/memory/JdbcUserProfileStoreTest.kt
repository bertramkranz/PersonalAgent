package com.personalagent.bertbot.memory

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcUserProfileStoreTest {
    @Test
    fun `jdbc user profile store persists conservative profile fields`() {
        val jdbcUrl = h2JdbcUrl("profile")
        val store = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = "bertbot_profile_snapshot"))

        store.updateDisplayName("Bertram Kranz")
        store.addRecurringPreference("concise updates")
        store.addCommunicationStyleHint("prefers step-by-step explanations")
        store.addStableInterest("kotlin")

        val reloaded = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = "bertbot_profile_snapshot"))
        val profile = reloaded.current()

        assertEquals("Bertram Kranz", profile.displayName)
        assertTrue(profile.recurringPreferences.contains("concise updates"))
        assertTrue(profile.communicationStyleHints.contains("prefers step-by-step explanations"))
        assertTrue(profile.stableInterests.contains("kotlin"))
    }

    @Test
    fun `jdbc user profile store isolates profiles per scope`() {
        val jdbcUrl = h2JdbcUrl("profile_scope")
        val store = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = "bertbot_profile_scope_snapshot"))

        store.withScope("scope-a") {
            store.updateDisplayName("Scope A")
        }
        store.withScope("scope-b") {
            store.updateDisplayName("Scope B")
        }

        val fromA = store.withScope("scope-a") { store.current().displayName }
        val fromB = store.withScope("scope-b") { store.current().displayName }

        assertEquals("Scope A", fromA)
        assertEquals("Scope B", fromB)
    }

    private fun h2JdbcUrl(suffix: String): String =
        "jdbc:h2:mem:bertbot_${suffix}_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
