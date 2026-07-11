package com.personalagent.bertbot.app

fun main(args: Array<String>) {
    val cliArgs = BertBotCliArgs.parse(args)
    val runtime = BertBotRuntimeFactory.create()
    if (runtime == null) {
        printMissingApiKeyHelp()
        return
    }

    val prompt = cliArgs.prompt
    if (prompt.isNullOrBlank()) {
        println("Usage: bertbot --prompt \"your request\"")
        runtime.close()
        return
    }

    try {
        val response = runtime.respondTo(prompt)
        if (!response.isNullOrBlank()) {
            println(response)
        }
    } catch (e: Exception) {
        printRuntimeError(e)
        e.printStackTrace()
    } finally {
        runtime.close()
    }
}
