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
