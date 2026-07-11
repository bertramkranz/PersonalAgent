package com.personalagent.bertbot.app

private val promptInjectionPatterns =
    listOf(
        Regex("\\bignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions?\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(disregard|override|bypass)\\s+(system|developer|safety|security)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(reveal|show|print|dump|leak)\\b.{0,40}\\b(system\\s*prompt|hidden\\s*prompt|developer\\s*message|instructions?)\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("\\b(role\\s*:?\\s*system|you\\s+are\\s+now|act\\s+as\\s+system)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(jailbreak|dan\\s+mode|prompt\\s*injection)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(exfiltrate|extract)\\b.{0,40}\\b(secret|api\\s*key|token|credential)\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    )

private const val PROMPT_INJECTION_REFUSAL_MESSAGE =
    "I can't comply with requests to override hidden instructions, reveal protected prompts, or exfiltrate secrets. " +
        "Please restate your request as a normal task without jailbreak or instruction-override content."

internal fun isLikelyPromptInjection(input: String): Boolean {
    if (input.isBlank()) {
        return false
    }

    return promptInjectionPatterns.any { it.containsMatchIn(input) }
}

internal fun promptInjectionRefusalMessage(): String = PROMPT_INJECTION_REFUSAL_MESSAGE

internal fun escapeForSystemContext(input: String): String =
    input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n")

internal fun renderStateListForSystemContext(values: List<String>): String {
    if (values.isEmpty()) {
        return "[]"
    }

    val items = values.joinToString(separator = ", ") { "\"${escapeForSystemContext(it.take(240))}\"" }
    return "[$items]"
}
