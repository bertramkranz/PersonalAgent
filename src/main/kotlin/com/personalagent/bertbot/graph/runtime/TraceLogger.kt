package com.personalagent.bertbot.graph.runtime

import com.google.gson.Gson
import com.personalagent.bertbot.app.resolveTraceFilePath
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max

data class TraceEventRecord(
    val timestamp: Long,
    val traceId: String,
    val level: String,
    val event: String,
    val details: String,
)

object TraceLogger {
    private val logger: Logger = Logger.getLogger("com.personalagent.bertbot")
    private val gson = Gson()
    private val traceFile = File(resolveTraceFilePath())
    private val writeLock = Any()
    private val recentEvents = mutableListOf<TraceEventRecord>()
    private const val MAX_RECENT_EVENTS = 2_000

    fun info(
        context: TracingContext,
        event: String,
        details: String,
    ) {
        record(context, Level.INFO, event, details)
        logger.log(Level.INFO, format(context, event, details))
    }

    fun warn(
        context: TracingContext,
        event: String,
        details: String,
    ) {
        record(context, Level.WARNING, event, details)
        logger.log(Level.WARNING, format(context, event, details))
    }

    fun transition(
        context: TracingContext,
        fromNodeId: String,
        toNodeId: String,
        details: String = "",
    ) {
        val suffix = if (details.isBlank()) "" else " $details"
        info(context, "edge_transition", "from=$fromNodeId to=$toNodeId$suffix")
    }

    fun intentParsed(
        context: TracingContext,
        details: String,
    ) {
        info(context, "intent_parsed", details)
    }

    fun subAgentSelected(
        context: TracingContext,
        details: String,
    ) {
        info(context, "sub_agent_selected", details)
    }

    fun skillInvoked(
        context: TracingContext,
        details: String,
    ) {
        info(context, "skill_invoked", details)
    }

    fun skillCompleted(
        context: TracingContext,
        details: String,
    ) {
        info(context, "skill_completed", details)
    }

    fun error(
        context: TracingContext,
        event: String,
        details: String,
        throwable: Throwable,
    ) {
        record(context, Level.SEVERE, event, details)
        logger.log(Level.SEVERE, format(context, event, details), throwable)
    }

    fun snapshot(traceId: String): List<TraceEventRecord> {
        synchronized(recentEvents) {
            return recentEvents.filter { event -> event.traceId == traceId }
        }
    }

    private fun record(
        context: TracingContext,
        level: Level,
        event: String,
        details: String,
    ) {
        val record =
            TraceEventRecord(
                timestamp = System.currentTimeMillis(),
                traceId = context.traceId,
                level = level.name,
                event = event,
                details = details,
            )

        synchronized(recentEvents) {
            recentEvents.add(record)
            val overflow = max(0, recentEvents.size - MAX_RECENT_EVENTS)
            repeat(overflow) {
                recentEvents.removeAt(0)
            }
        }

        synchronized(writeLock) {
            runCatching {
                traceFile.parentFile?.mkdirs()
                traceFile.appendText(gson.toJson(record) + System.lineSeparator())
            }.onFailure { e ->
                logger.log(Level.WARNING, "TraceLogger: failed to persist trace event to file '${traceFile.path}': ${e.message}")
            }
        }
    }

    private fun format(
        context: TracingContext,
        event: String,
        details: String,
    ): String =
        "traceId=${context.traceId} event=$event details=$details"
}
