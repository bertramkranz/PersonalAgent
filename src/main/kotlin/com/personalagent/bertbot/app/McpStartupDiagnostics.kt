package com.personalagent.bertbot.app

internal data class McpStartupDiagnostics(
    val serverName: String,
    val serverVersion: String,
    val tools: List<String>,
    val workspaceRootPath: String,
    val provider: String,
    val model: String,
    val macrofactorEnabled: Boolean,
    val macrofactorConfigured: Boolean,
    val googleWorkspaceEnabled: Boolean,
    val runtimeReady: Boolean,
    val runtimeError: String,
)

internal fun logMcpStartupDiagnostics(input: McpStartupDiagnostics) {
    // Keep startup diagnostics on stderr to avoid polluting JSON-RPC responses on stdout.
    System.err.println(
        """
        BertBot MCP server started.
        Server: ${input.serverName} v${input.serverVersion}
        Tools: ${input.tools.joinToString()}
        Workspace root: ${input.workspaceRootPath}
        Provider: ${input.provider}
        Model: ${input.model}
        MacroFactor enabled: ${input.macrofactorEnabled}
        MacroFactor configured: ${input.macrofactorConfigured}
        Google Workspace MCP enabled: ${input.googleWorkspaceEnabled}
        Runtime ready: ${input.runtimeReady}
        Runtime error: ${input.runtimeError}
        """.trimIndent(),
    )
}
