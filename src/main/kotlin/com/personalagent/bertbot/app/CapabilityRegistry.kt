package com.personalagent.bertbot.app

import com.google.gson.JsonObject

internal interface ToolRouter {
    val id: String

    fun toolDefinitions(): List<JsonObject>

    fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>?
}

internal class FunctionToolRouter(
    override val id: String,
    private val definitionsProvider: () -> List<JsonObject>,
    private val executor: (toolName: String?, params: JsonObject) -> Pair<Boolean, String>?,
) : ToolRouter {
    override fun toolDefinitions(): List<JsonObject> = definitionsProvider.invoke()

    override fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? = executor(toolName, params)
}

internal data class CapabilityDefinition(
    val id: String,
    val router: ToolRouter,
)

internal class CapabilityRegistry(
    capabilities: List<CapabilityDefinition>,
) {
    private val capabilitiesById: Map<String, CapabilityDefinition> =
        capabilities.associateBy { capability -> capability.id }

    private val capabilityOrder: List<CapabilityDefinition> = capabilities
    private val capabilityIdByToolName: Map<String, String> =
        capabilityOrder
            .flatMap { capability ->
                capability.router.toolDefinitions().mapNotNull { definition ->
                    definition.get("name")?.asString?.let { toolName -> toolName to capability.id }
                }
            }.toMap()

    fun capabilityIds(): Set<String> = capabilitiesById.keys

    fun toolDefinitions(): List<JsonObject> =
        capabilityOrder.flatMap { capability -> capability.router.toolDefinitions() }

    fun toolDefinitionsFor(capabilityId: String): List<JsonObject> =
        capabilitiesById[capabilityId]?.router?.toolDefinitions().orEmpty()

    fun toolDefinitionsForCapabilities(capabilityIds: Set<String>): List<JsonObject> =
        capabilityOrder
            .filter { capability -> capability.id in capabilityIds }
            .flatMap { capability -> capability.router.toolDefinitions() }

    fun capabilityIdForToolName(toolName: String?): String? =
        toolName?.let { name -> capabilityIdByToolName[name] }

    fun execute(
        toolName: String?,
        params: JsonObject,
        allowedCapabilityIds: Set<String>? = null,
    ): Pair<Boolean, String>? =
        capabilityOrder
            .asSequence()
            .filter { capability -> allowedCapabilityIds == null || capability.id in allowedCapabilityIds }
            .firstNotNullOfOrNull { capability ->
                capability.router.handle(toolName, params)
            }
}

internal class UnifiedToolBus(
    private val capabilityRegistry: CapabilityRegistry,
    private val builtInHandlers: Map<String, (params: JsonObject) -> Pair<Boolean, String>> = emptyMap(),
) {
    fun toolDefinitions(): List<JsonObject> = capabilityRegistry.toolDefinitions()

    fun toolDefinitionsFor(capabilityId: String): List<JsonObject> =
        capabilityRegistry.toolDefinitionsFor(capabilityId)

    fun capabilityIds(): Set<String> = capabilityRegistry.capabilityIds()

    fun execute(
        toolName: String?,
        params: JsonObject,
        allowedCapabilityIds: Set<String>? = null,
    ): Pair<Boolean, String>? {
        val routed = capabilityRegistry.execute(toolName, params, allowedCapabilityIds)
        if (routed != null) {
            return routed
        }

        val name = toolName ?: return null
        val builtInHandler = builtInHandlers[name] ?: return null
        return builtInHandler(params)
    }
}
