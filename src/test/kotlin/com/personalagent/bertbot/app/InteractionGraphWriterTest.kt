package com.personalagent.bertbot.app

import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TraceEventRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionGraphWriterTest {
    @Test
    fun `writer creates sequence diagram with delegation lifecycle`() {
        val output = File.createTempFile("bertbot-interactions", ".mmd")
        output.deleteOnExit()

        val writer = InteractionGraphWriter(output)
        val events =
            listOf(
                TraceEventRecord(
                    timestamp = 1,
                    traceId = "trace-1",
                    level = "INFO",
                    event = "node_start",
                    details = "node_id=delegation",
                ),
                TraceEventRecord(
                    timestamp = 2,
                    traceId = "trace-1",
                    level = "INFO",
                    event = "delegation_requested",
                    details = "from=bertbot to=Red Teamer task_length=12",
                ),
                TraceEventRecord(
                    timestamp = 3,
                    traceId = "trace-1",
                    level = "INFO",
                    event = "delegation_started",
                    details = "from=bertbot to=Red Teamer",
                ),
                TraceEventRecord(
                    timestamp = 4,
                    traceId = "trace-1",
                    level = "INFO",
                    event = "delegation_completed",
                    details = "from=Red Teamer to=bertbot",
                ),
            )

        writer.write(
            traceId = "trace-1",
            state = BertBotState(lastUserMessage = "Please review this change"),
            events = events,
        )

        val generated = output.readText()
        assertTrue(generated.contains("sequenceDiagram"))
        assertTrue(generated.contains("participant bertbot as BertBot"))
        assertTrue(generated.contains("participant red_teamer as Red Teamer"))
        assertTrue(generated.contains("bertbot->>red_teamer: delegate task"))
        assertTrue(generated.contains("red_teamer-->>bertbot: delegation completed"))
    }

    @Test
    fun `writer does not render placeholder none participant`() {
        val output = File.createTempFile("bertbot-interactions", ".mmd")
        output.deleteOnExit()

        val writer = InteractionGraphWriter(output)
        val events =
            listOf(
                TraceEventRecord(
                    timestamp = 1,
                    traceId = "trace-2",
                    level = "INFO",
                    event = "delegation_started",
                    details = "from=bertbot to=none",
                ),
                TraceEventRecord(
                    timestamp = 2,
                    traceId = "trace-2",
                    level = "INFO",
                    event = "delegation_completed",
                    details = "from=none to=bertbot",
                ),
            )

        writer.write(
            traceId = "trace-2",
            state = BertBotState(lastUserMessage = "test"),
            events = events,
        )

        val generated = output.readText()
        assertFalse(generated.contains("participant none as none"))
        assertTrue(generated.contains("delegation started (no sub-agent target)"))
        assertTrue(generated.contains("delegation completed (no sub-agent target)"))
    }

    @Test
    fun `writer renders configured sub-agent display names for id participants`() {
        val output = File.createTempFile("bertbot-interactions", ".mmd")
        output.deleteOnExit()

        val writer = InteractionGraphWriter(output)
        val events =
            listOf(
                TraceEventRecord(
                    timestamp = 1,
                    traceId = "trace-3",
                    level = "INFO",
                    event = "delegation_requested",
                    details = "from=bertbot to=architect task_length=20",
                ),
                TraceEventRecord(
                    timestamp = 2,
                    traceId = "trace-3",
                    level = "INFO",
                    event = "delegation_started",
                    details = "from=bertbot to=architect",
                ),
                TraceEventRecord(
                    timestamp = 3,
                    traceId = "trace-3",
                    level = "INFO",
                    event = "delegation_completed",
                    details = "from=architect to=bertbot",
                ),
            )

        writer.write(
            traceId = "trace-3",
            state = BertBotState(lastUserMessage = "test"),
            events = events,
        )

        val generated = output.readText()
        assertTrue(generated.contains("participant architect as Architect Agent"))
        assertTrue(generated.contains("bertbot->>architect: delegate task"))
    }

    @Test
    fun `writer renders delegation skipped note`() {
        val output = File.createTempFile("bertbot-interactions", ".mmd")
        output.deleteOnExit()

        val writer = InteractionGraphWriter(output)
        val events =
            listOf(
                TraceEventRecord(
                    timestamp = 1,
                    traceId = "trace-4",
                    level = "INFO",
                    event = "delegation_skipped",
                    details = "reason=no_sub_agent_match task_length=20",
                ),
            )

        writer.write(
            traceId = "trace-4",
            state = BertBotState(lastUserMessage = "test"),
            events = events,
        )

        val generated = output.readText()
        assertTrue(generated.contains("delegation skipped (no_sub_agent_match)"))
    }
}
