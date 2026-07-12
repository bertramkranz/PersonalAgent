package com.personalagent.bertbot.app

internal object McpPromptHeuristics {
    val backendUnavailableMarkers =
        listOf(
            "backend_workspace_unavailable",
            "backend workspace tooling is unavailable",
            "backend cannot verify repository files",
            "cannot verify repository files",
        )

    val evidenceHintKeywords =
        listOf(
            "repo",
            "repository",
            "file",
            "files",
            "directory",
            "directories",
            "architecture",
            "review",
            "inventory",
            "line reference",
            "line references",
            "verified",
            "verify",
        )

    val statusHintKeywords =
        listOf(
            "bert_bot status",
            "bertbot status",
            "bertbot_status",
            "backend health",
            "backend status",
            "runtime state",
            "status output",
            "check backend",
            "is backend",
        )
}
