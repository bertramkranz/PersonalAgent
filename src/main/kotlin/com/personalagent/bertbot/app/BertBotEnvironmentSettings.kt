package com.personalagent.bertbot.app

import java.io.File

internal fun resolveRuntimeSetting(
    name: String,
    environment: Map<String, String>,
    dotEnvValues: Map<String, String>,
): String? {
    val envValue = environment[name]
    if (!envValue.isNullOrBlank()) {
        return envValue.trim().removeSurrounding("\"")
    }

    return dotEnvValues[name]?.trim()?.removeSurrounding("\"")
}

internal fun resolveRuntimeSettingAllowBlank(
    name: String,
    environment: Map<String, String>,
    dotEnvValues: Map<String, String>,
): String? {
    if (environment.containsKey(name)) {
        return environment[name]?.trim()?.removeSurrounding("\"") ?: ""
    }

    if (dotEnvValues.containsKey(name)) {
        return dotEnvValues[name]?.trim()?.removeSurrounding("\"") ?: ""
    }

    return null
}

internal fun loadDotEnvValues(): Map<String, String> {
    val envFile = File(".env")
    if (!envFile.exists()) {
        return emptyMap()
    }

    return envFile.readLines().asSequence().mapNotNull { parseDotEnvEntry(it) }.toMap()
}

private fun parseDotEnvEntry(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        return null
    }

    val normalized = trimmed.removePrefix("export ")
    val separatorIndex = normalized.indexOf('=')
    if (separatorIndex <= 0) {
        return null
    }

    val key = normalized.substring(0, separatorIndex).trim()
    val value = normalized.substring(separatorIndex + 1).trim().removeSurrounding("\"")
    return key to value
}
