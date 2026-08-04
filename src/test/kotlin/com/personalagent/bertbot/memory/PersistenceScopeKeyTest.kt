package com.personalagent.bertbot.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PersistenceScopeKeyTest {
    @Test
    fun `legacy aliases use truncate semantics for compatibility`() {
        val longScope = "x".repeat(300)

        val legacyFile = PersistenceScopeKey.legacyFileAlias(longScope)
        val legacyJdbc = PersistenceScopeKey.legacyJdbcAlias(longScope)

        assertEquals(200, legacyFile.length)
        assertEquals(longScope.take(200), legacyFile)
        assertEquals(255, legacyJdbc.length)
        assertEquals(longScope.take(255), legacyJdbc)
    }

    @Test
    fun `normalized scope uses hash suffix when exceeding limit`() {
        val scopeA = "scope|" + "a".repeat(260)
        val scopeB = "scope|" + "b".repeat(260)

        val normalizedA = PersistenceScopeKey.normalizeForFile(scopeA)
        val normalizedB = PersistenceScopeKey.normalizeForFile(scopeB)

        assertTrue(normalizedA.length <= 120)
        assertTrue(normalizedB.length <= 120)
        assertNotEquals(normalizedA, normalizedB)
    }
}
