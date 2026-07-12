package com.personalagent.bertbot.serialization

import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinxAgentJsonCodecTest {
    @Test
    fun `codec parses kotlinx JsonElement payloads`() {
        val codec = KotlinxAgentJsonCodec()

        val decoded = codec.decode("{\"ok\":true}", JsonElement::class.java)

        assertNotNull(decoded)
        assertTrue(decoded.toString().contains("\"ok\":true"))
    }

    @Test
    fun `codec falls back to gson for regular data classes`() {
        val codec = KotlinxAgentJsonCodec()
        val source = SamplePayload(name = "bert", retries = 2)

        val encoded = codec.encode(source)
        val decoded = codec.decode(encoded, SamplePayload::class.java)

        assertEquals(source, decoded)
    }

    private data class SamplePayload(
        val name: String,
        val retries: Int,
    )
}
