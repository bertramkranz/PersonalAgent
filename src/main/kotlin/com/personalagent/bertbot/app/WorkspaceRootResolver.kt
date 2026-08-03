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
    dotEnvValues: Map<String, String> = loadDotEnvValues(),
    currentDirectory: File = File("."),
): File {
    val configuredRoot =
        resolveRuntimeSetting(McpConstants.WORKSPACE_ROOT_ENV_VAR, environment, dotEnvValues)
            ?.takeIf { it.isNotBlank() }
    if (configuredRoot != null) {
        val configured = File(configuredRoot)
        if (configured.exists() && configured.isDirectory) {
            return configured.canonicalFile
        }
    }

    val fromMarkers = findWorkspaceRootByMarkers(currentDirectory)
    return fromMarkers ?: currentDirectory.canonicalFile
}
