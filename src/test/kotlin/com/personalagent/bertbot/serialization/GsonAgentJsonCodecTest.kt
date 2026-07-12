package com.personalagent.bertbot.serialization

import kotlin.test.Test
import kotlin.test.assertEquals

class GsonAgentJsonCodecTest {
    @Test
    fun `codec round-trips a typed data class`() {
        val codec = GsonAgentJsonCodec()
        val source = SamplePayload(name = "bert", retries = 3)

        val encoded = codec.encode(source)
        val decoded = codec.decode(encoded, SamplePayload::class.java)

        assertEquals(source, decoded)
    }

    private data class SamplePayload(
        val name: String,
        val retries: Int,
    )
}
