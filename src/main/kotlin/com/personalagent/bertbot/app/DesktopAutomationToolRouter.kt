package com.personalagent.bertbot.app

import com.google.gson.JsonObject

internal class DesktopAutomationToolRouter(
    private val runtimeConfiguration: DesktopAutomationRuntimeConfiguration,
    private val transport: DesktopAutomationMcpTransport = StdioDesktopAutomationMcpTransport(runtimeConfiguration),
) {
    fun toolDefinitions(): List<JsonObject> {
        if (!runtimeConfiguration.enabled) {
            return emptyList()
        }

        return transport.listTools()?.map { tool ->
            val proxy = JsonObject()
            proxy.addProperty("name", proxyToolName(tool.name))
            proxy.addProperty("description", "Desktop automation proxy for '${tool.name}': ${tool.description}")
            proxy.add("inputSchema", tool.inputSchema)
            proxy
        }.orEmpty()
    }

    fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        if (!runtimeConfiguration.enabled) {
            return true to "Desktop automation is disabled. Set BERTBOT_DESKTOP_AUTOMATION_ENABLED=true to enable it."
        }

        if (toolName.isNullOrBlank()) {
            return null
        }

        val normalized = toolName.removePrefix(runtimeConfiguration.toolNamePrefix)
        if (normalized == toolName) {
            return null
        }

        return transport.callTool(normalized, params)
    }

    private fun proxyToolName(name: String): String = "${runtimeConfiguration.toolNamePrefix}$name"
}

internal interface DesktopAutomationMcpTransport {
    fun listTools(): List<DesktopAutomationDiscoveredTool>?

    fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String>
}

internal data class DesktopAutomationDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

private class StdioDesktopAutomationMcpTransport(
    private val runtimeConfiguration: DesktopAutomationRuntimeConfiguration,
) : DesktopAutomationMcpTransport {
    override fun listTools(): List<DesktopAutomationDiscoveredTool>? =
        listOf(
            DesktopAutomationDiscoveredTool(
                name = "click",
                description = "Click a GUI element by selector or coordinate.",
                inputSchema = defaultInputSchema(),
            ),
            DesktopAutomationDiscoveredTool(
                name = "type",
                description = "Type text into an active GUI element.",
                inputSchema = defaultInputSchema(),
            ),
        )

    override fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Pair<Boolean, String> {
        if (!runtimeConfiguration.enabled) {
            return true to "Desktop automation is disabled."
        }

        return when (toolName) {
            "click" -> false to "desktop automation click requested"
            "type" -> false to "desktop automation type requested"
            else -> true to "Unknown desktop automation tool: $toolName"
        }
    }

    private fun defaultInputSchema(): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        schema.add("properties", JsonObject())
        return schema
    }
}
