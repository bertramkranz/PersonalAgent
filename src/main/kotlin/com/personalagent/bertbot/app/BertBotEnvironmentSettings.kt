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

internal fun loadDotEnvValues(envFile: File = File(".env")): Map<String, String> {
    if (!envFile.exists()) {
        return emptyMap()
    }

    return envFile.readLines().asSequence().mapNotNull { parseDotEnvEntry(it) }.toMap()
}

/**
 * Parses [this] string as a boolean environment variable value.
 *
 * Accepts permissive truthy tokens (`"1"`, `"true"`, `"yes"`, `"on"`) and falsy tokens
 * (`"0"`, `"false"`, `"no"`, `"off"`) case-insensitively.  Any other value — including
 * `null` — returns [defaultValue].  This function is intentionally lenient so that common
 * shell idioms (e.g. `VAR=1` or `VAR=yes`) behave consistently across all run modes.
 */
internal fun String?.toBooleanEnv(defaultValue: Boolean): Boolean {
    val value = this?.trim()?.lowercase() ?: return defaultValue
    return when (value) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
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
