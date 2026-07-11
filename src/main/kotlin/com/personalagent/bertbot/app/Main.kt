package com.personalagent.bertbot.app

fun main() {
    val runtime = BertBotRuntimeFactory.create()
    if (runtime == null) {
        printMissingApiKeyHelp()
        return
    }

    printRuntimeStartupInfo(runtime.config, runtime.aiRuntimeConfiguration)

    try {
        runInteractiveLoop(runtime)
    } catch (e: Exception) {
        printRuntimeError(e)
        e.printStackTrace()
    } finally {
        runtime.close()
    }
}

private fun runInteractiveLoop(runtime: BertBotRuntime) {
    println("Type your message and press Enter. Type 'exit' to quit.")
    println("")

    while (true) {
        print("You: ")
        val rawInput = readlnOrNull()
        if (rawInput == null) {
            println("\nInput stream closed. Session ended.")
            break
        }

        val userMessage = rawInput.trim()

        if (userMessage.equals("exit", ignoreCase = true)) {
            println("Session ended.")
            break
        }

        if (userMessage.isBlank()) {
            println("Please enter a message or type 'exit'.")
            continue
        }

        if (isLikelyPromptInjection(userMessage)) {
            println("Assistant: I can't comply with requests to override hidden instructions, reveal protected prompts, or exfiltrate secrets.")
            println("Please restate your request as a normal task without jailbreak or instruction-override content.")
            println("")
            continue
        }

        val response = runtime.respondTo(userMessage)
        if (response != null) {
            println("Assistant: $response")
            println("")
        }
    }
}
