package com.personalagent.bertbot.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BertBotRuntimeHelpersTest {
    @Test
    fun `extractDisplayNameFromMessage captures explicit name`() {
        assertEquals("Bertram Kranz", extractDisplayNameFromMessage("My name is Bertram Kranz."))
    }

    @Test
    fun `extractDisplayNameFromMessage returns null when no explicit name`() {
        assertEquals(null, extractDisplayNameFromMessage("I am doing well today"))
    }

    @Test
    fun `isNameRecallQuestion detects common name lookup prompts`() {
        assertTrue(isNameRecallQuestion("What is my name?"))
        assertTrue(isNameRecallQuestion("Do you know my name"))
        assertFalse(isNameRecallQuestion("What is my task"))
    }
}
