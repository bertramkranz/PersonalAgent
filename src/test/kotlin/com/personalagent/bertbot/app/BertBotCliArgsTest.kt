package com.personalagent.bertbot.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BertBotCliArgsTest {
    @Test
    fun `parse accepts prompt flag with separate value`() {
        val args = BertBotCliArgs.parse(arrayOf("--prompt", "review", "this", "change"))

        assertEquals("review this change", args.prompt)
    }

    @Test
    fun `parse accepts prompt flag with equals syntax`() {
        val args = BertBotCliArgs.parse(arrayOf("-p=generate a plan"))

        assertEquals("generate a plan", args.prompt)
    }

    @Test
    fun `parse accepts positional prompt text`() {
        val args = BertBotCliArgs.parse(arrayOf("help", "me", "debug"))

        assertEquals("help me debug", args.prompt)
    }

    @Test
    fun `parse returns null when no prompt is supplied`() {
        val args = BertBotCliArgs.parse(emptyArray())

        assertNull(args.prompt)
    }
}
