package com.personalagent.bertbot.graph.runtime

import java.util.logging.Level
import java.util.logging.Logger

object TraceLogger {
    private val logger: Logger = Logger.getLogger("com.personalagent.bertbot")

    fun info(
        context: TracingContext,
        event: String,
        details: String,
    ) {
        logger.log(Level.INFO, format(context, event, details))
    }

    fun warn(
        context: TracingContext,
        event: String,
        details: String,
    ) {
        logger.log(Level.WARNING, format(context, event, details))
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
        logger.log(Level.SEVERE, format(context, event, details), throwable)
    }

    private fun format(
        context: TracingContext,
        event: String,
        details: String,
    ): String =
        "traceId=${context.traceId} event=$event details=$details"
}
