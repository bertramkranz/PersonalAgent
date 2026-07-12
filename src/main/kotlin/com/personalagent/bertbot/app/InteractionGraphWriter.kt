package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.TraceEventRecord
import java.io.File

internal class InteractionGraphWriter(
    private val outputFile: File = File(resolveInteractionsFilePath()),
    private val participantDisplayNames: Map<String, String> = defaultParticipantDisplayNames(),
) {
    companion object {
        private const val PROFILE_LOOKUP_NOTE = "    Note over BertBot: profile lookup for deterministic recall"
        private val PLACEHOLDER_PARTICIPANTS = setOf("none", "unknown", "unassigned", "n/a")

        private fun defaultParticipantDisplayNames(): Map<String, String> {
            val configuredSubAgents = BertBotAgentConfig().enabledSubAgents().associate { definition -> definition.id to definition.name }
            return configuredSubAgents + mapOf("User" to "User", "BertBot" to "BertBot")
        }
    }

    fun write(
        traceId: String,
        state: BertBotState,
        events: List<TraceEventRecord>,
    ) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(buildMermaid(traceId, state, events))
    }

    private fun buildMermaid(
        traceId: String,
        state: BertBotState,
        events: List<TraceEventRecord>,
    ): String {
        val orderedEvents = events.sortedBy { event -> event.timestamp }
        val participants = collectParticipants(orderedEvents)

        val participantAliases =
            participants.associateWith { participant ->
                toParticipantAlias(participant)
            }

        val lines = mutableListOf<String>()
        lines.add("```mermaid")
        lines.add("%% BertBot interaction sequence for traceId=$traceId")
        lines.add("sequenceDiagram")
        participantAliases.forEach { (name, alias) ->
            lines.add("    participant $alias as ${resolveParticipantDisplayName(name)}")
        }

        lines.add("    User->>BertBot: ${escapeMermaidMessage(state.lastUserMessage.ifBlank { "(empty message)" })}")

        appendTraceEventLines(lines, orderedEvents, participantAliases)

        lines.add("    BertBot-->>User: assistant response")
        lines.add("```")
        return lines.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator())
    }

    private fun collectParticipants(events: List<TraceEventRecord>): LinkedHashSet<String> {
        val participants = linkedSetOf("User", "BertBot")
        events.forEach { event ->
            if (!isDelegationEvent(event.event)) {
                return@forEach
            }
            addParticipantIfPresent(participants, extractField(event.details, "to"))
            addParticipantIfPresent(participants, extractField(event.details, "from"))
        }
        return participants
    }

    private fun isDelegationEvent(event: String): Boolean =
        event == "delegation_requested" || event == "delegation_started" || event == "delegation_completed"

    private fun addParticipantIfPresent(
        participants: LinkedHashSet<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            return
        }

        val normalized = normalizeParticipantName(value)
        if (normalized.equals("BertBot", ignoreCase = true) || isPlaceholderParticipant(normalized)) {
            return
        }
        participants.add(normalized)
    }

    private fun appendTraceEventLines(
        lines: MutableList<String>,
        events: List<TraceEventRecord>,
        participantAliases: Map<String, String>,
    ) {
        events.forEach { event ->
            renderTraceEventLine(event, participantAliases)?.let { rendered ->
                lines.add(rendered)
            }
        }
    }

    private fun renderTraceEventLine(
        event: TraceEventRecord,
        participantAliases: Map<String, String>,
    ): String? {
        return when (event.event) {
            "node_start" -> renderNodeStart(event)
            "edge_transition" -> renderEdgeTransition(event)
            "delegation_requested" -> renderDelegationRequested(event, participantAliases)
            "delegation_started" -> renderDelegationStarted(event, participantAliases)
            "delegation_completed" -> renderDelegationCompleted(event, participantAliases)
            "delegation_skipped" -> renderDelegationSkipped(event)
            "graph_node_visits" -> {
                val summary = extractField(event.details, "counts") ?: "unknown"
                "    Note over BertBot: node visits $summary"
            }
            "profile_lookup" -> PROFILE_LOOKUP_NOTE
            else -> null
        }
    }

    private fun renderNodeStart(event: TraceEventRecord): String {
        val nodeId = extractField(event.details, "node_id") ?: "unknown"
        return "    Note over BertBot: node_start $nodeId"
    }

    private fun renderEdgeTransition(event: TraceEventRecord): String {
        val fromNode = extractField(event.details, "from") ?: "?"
        val toNode = extractField(event.details, "to") ?: "?"
        return "    Note over BertBot: transition $fromNode -> $toNode"
    }

    private fun renderDelegationRequested(
        event: TraceEventRecord,
        participantAliases: Map<String, String>,
    ): String {
        val from = normalizeParticipantName(extractField(event.details, "from") ?: "bertbot")
        val to = normalizeParticipantName(extractField(event.details, "to") ?: "unknown")
        if (isPlaceholderParticipant(to)) {
            return "    Note over BertBot: delegation skipped (no sub-agent target)"
        }
        val fromAlias = participantAliases.getOrElse(from) { toParticipantAlias(from) }
        val toAlias = participantAliases.getOrElse(to) { toParticipantAlias(to) }
        return "    $fromAlias->>$toAlias: delegate task"
    }

    private fun renderDelegationStarted(
        event: TraceEventRecord,
        participantAliases: Map<String, String>,
    ): String {
        val to = normalizeParticipantName(extractField(event.details, "to") ?: "unknown")
        if (isPlaceholderParticipant(to)) {
            return "    Note over BertBot: delegation started (no sub-agent target)"
        }
        val toAlias = participantAliases.getOrElse(to) { toParticipantAlias(to) }
        return "    Note over BertBot,$toAlias: delegation started"
    }

    private fun renderDelegationCompleted(
        event: TraceEventRecord,
        participantAliases: Map<String, String>,
    ): String {
        val from = normalizeParticipantName(extractField(event.details, "from") ?: "unknown")
        if (isPlaceholderParticipant(from)) {
            return "    Note over BertBot: delegation completed (no sub-agent target)"
        }
        val fromAlias = participantAliases.getOrElse(from) { toParticipantAlias(from) }
        val bertbotAlias = participantAliases.getValue("BertBot")
        return "    $fromAlias-->>$bertbotAlias: delegation completed"
    }

    private fun renderDelegationSkipped(event: TraceEventRecord): String {
        val reason = extractField(event.details, "reason") ?: "unspecified"
        return "    Note over BertBot: delegation skipped ($reason)"
    }

    private fun extractField(
        details: String,
        key: String,
    ): String? {
        val regex = Regex("""([a-zA-Z_]+)=([^=]+?)(?=\s+[a-zA-Z_]+=|$)""")
        val fields =
            regex.findAll(details).associate { match ->
                val foundKey = match.groupValues[1].trim()
                val foundValue = match.groupValues[2].trim()
                foundKey to foundValue
            }
        return fields[key]
    }

    private fun toParticipantAlias(name: String): String {
        val normalized = normalizeParticipantName(name)
        val alias = normalized.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (alias.isBlank()) "participant_unknown" else alias
    }

    private fun resolveParticipantDisplayName(name: String): String {
        val normalized = normalizeParticipantName(name)
        return participantDisplayNames[normalized] ?: normalized
    }

    private fun normalizeParticipantName(name: String): String =
        if (name.equals("bertbot", ignoreCase = true)) {
            "BertBot"
        } else {
            name
        }

    private fun isPlaceholderParticipant(name: String): Boolean =
        PLACEHOLDER_PARTICIPANTS.contains(name.lowercase())

    private fun escapeMermaidMessage(value: String): String =
        value
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\"", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
}
