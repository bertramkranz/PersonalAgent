package com.personalagent.bertbot.app

import java.io.File

internal fun findWorkspaceRootByMarkers(start: File): File? {
    var cursor: File? = start.canonicalFile
    while (cursor != null) {
        val hasGit = File(cursor, ".git").exists()
        val hasGradleSettings = File(cursor, "settings.gradle.kts").exists()
        if (hasGit || hasGradleSettings) {
            return cursor
        }
        cursor = cursor.parentFile
    }
    return null
}

internal fun resolveWorkspaceRoot(
    environment: Map<String, String> = System.getenv(),
    currentDirectory: File = File("."),
): File {
    val configuredRoot = environment[McpConstants.WORKSPACE_ROOT_ENV_VAR]?.trim().orEmpty()
    if (configuredRoot.isNotBlank()) {
        val configured = File(configuredRoot)
        if (configured.exists() && configured.isDirectory) {
            return configured.canonicalFile
        }
    }

    val fromMarkers = findWorkspaceRootByMarkers(currentDirectory)
    return fromMarkers ?: currentDirectory.canonicalFile
}
