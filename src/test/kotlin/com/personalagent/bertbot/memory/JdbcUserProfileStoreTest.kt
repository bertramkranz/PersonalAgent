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

    @Test
    fun `jdbc user profile store persists shopping preference fields`() {
        val jdbcUrl = h2JdbcUrl("profile_shopping")
        val store = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = "bertbot_profile_shopping_snapshot"))

        store.addPreferredBrand("Nike")
        store.addPreferredSize("L")
        store.addPreferredStore("Amazon")
        store.setShoppingBudgetCents(5000)
        store.addShoppingNote("prefer fast delivery")

        val reloaded = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = "bertbot_profile_shopping_snapshot"))
        val profile = reloaded.current()

        assertTrue(profile.preferredBrands.contains("Nike"))
        assertTrue(profile.preferredSizes.contains("L"))
        assertTrue(profile.preferredStores.contains("Amazon"))
        assertEquals(5000L, profile.budgetLimitCents)
        assertTrue(profile.shoppingNotes.contains("prefer fast delivery"))
    }

    @Test
    fun `jdbc legacy profile JSON without shopping fields loads with defaults`() {
        val jdbcUrl = h2JdbcUrl("profile_legacy")
        val tableName = "bertbot_profile_legacy_snapshot"

        val store = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = tableName))
        store.updateDisplayName("Legacy User")
        store.addStableInterest("kotlin")

        val reloaded = UserProfileStore(JdbcUserProfileStore(jdbcUrl = jdbcUrl, tableName = tableName))
        val profile = reloaded.current()

        assertEquals("Legacy User", profile.displayName)
        assertTrue(profile.stableInterests.contains("kotlin"))
        assertTrue(profile.preferredBrands.isEmpty())
        assertTrue(profile.preferredSizes.isEmpty())
        assertTrue(profile.preferredStores.isEmpty())
        assertEquals(null, profile.budgetLimitCents)
        assertTrue(profile.shoppingNotes.isEmpty())
    }

    private fun h2JdbcUrl(suffix: String): String =
        "jdbc:h2:mem:bertbot_${suffix}_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
