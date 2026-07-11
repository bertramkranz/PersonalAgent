package com.personalagent.bertbot.app

internal data class BertBotCliArgs(
    val prompt: String? = null,
) {
    companion object {
        fun parse(args: Array<String>): BertBotCliArgs {
            if (args.isEmpty()) {
                return BertBotCliArgs()
            }

            val prompt =
                when {
                    args[0] == "--prompt" || args[0] == "-p" -> args.drop(1).joinToString(" ").trim().ifBlank { null }
                    args[0].startsWith("--prompt=") -> args[0].substringAfter("=").trim().ifBlank { null }
                    args[0].startsWith("-p=") -> args[0].substringAfter("=").trim().ifBlank { null }
                    else -> args.joinToString(" ").trim().ifBlank { null }
                }

            return BertBotCliArgs(prompt = prompt)
        }
    }
}
