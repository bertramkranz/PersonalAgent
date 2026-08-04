package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val PROCEDURAL_SKILL_LIST_TOOL_NAME = "procedural_skill_list"
internal const val PROCEDURAL_SKILL_CREATE_TOOL_NAME = "procedural_skill_create"
internal const val PROCEDURAL_SKILL_PATCH_TOOL_NAME = "procedural_skill_patch"
internal const val PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME = "procedural_skill_supersede"
internal const val PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME = "procedural_skill_archive"
internal const val PROCEDURAL_SKILL_APPROVE_TOOL_NAME = "procedural_skill_approve"
internal const val PROCEDURAL_SKILL_REJECT_TOOL_NAME = "procedural_skill_reject"

internal data class ProceduralSkillToolHandlers(
    val listSkills: (status: ProceduralSkillStatus?, limit: Int, scopeKey: String?) -> List<ProceduralSkillArtifact>,
    val createSkill: (request: ProceduralSkillCreateRequest, scopeKey: String?) -> ProceduralSkillArtifact,
    val patchSkill: (skillId: String, request: ProceduralSkillPatchRequest, scopeKey: String?) -> ProceduralSkillArtifact?,
    val supersedeSkill: (skillId: String, request: ProceduralSkillSupersedeRequest, scopeKey: String?) -> ProceduralSkillArtifact?,
    val archiveSkill: (skillId: String, staged: Boolean, scopeKey: String?) -> ProceduralSkillArtifact?,
    val approveSkill: (skillId: String, note: String?, scopeKey: String?) -> ProceduralSkillArtifact?,
    val rejectSkill: (skillId: String, note: String?, scopeKey: String?) -> ProceduralSkillArtifact?,
)

internal class ProceduralSkillToolRouter(
    private val handlers: ProceduralSkillToolHandlers,
) : ToolRouter {
    override val id: String = "procedural_skill"

    override fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        val request = ToolInvocationRequestMapper.from(toolName, params)
        val args = request.arguments

        return when (request.toolName) {
            PROCEDURAL_SKILL_LIST_TOOL_NAME -> handleList(args)
            PROCEDURAL_SKILL_CREATE_TOOL_NAME -> handleCreate(args)
            PROCEDURAL_SKILL_PATCH_TOOL_NAME -> handlePatch(args)
            PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME -> handleSupersede(args)
            PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME -> handleArchive(args)
            PROCEDURAL_SKILL_APPROVE_TOOL_NAME -> handleApprove(args)
            PROCEDURAL_SKILL_REJECT_TOOL_NAME -> handleReject(args)
            else -> null
        }
    }

    override fun toolDefinitions(): List<JsonObject> =
        listOf(
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_LIST_TOOL_NAME,
                description = "List procedural skills for a scope.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("status", "string", "Optional status filter: PENDING_APPROVAL, ACTIVE, ARCHIVED, SUPERSEDED, REJECTED.")
                property("limit", "number", "Maximum items to return (default 50, max 1000).")
            },
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_CREATE_TOOL_NAME,
                description = "Create a procedural skill artifact (staged by default).",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("slug", "string", "Stable slug for this skill.")
                property("title", "string", "Human-readable title.")
                property("instructions", "string", "Operational instructions for executing the skill.")
                property("staged", "boolean", "Defaults to true. When true, create requires approval.")
                required("slug", "title", "instructions")
            },
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_PATCH_TOOL_NAME,
                description = "Patch an active skill; staged patches create a pending candidate version.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("skillId", "string", "Target active skill id.")
                property("title", "string", "Optional replacement title.")
                property("instructions", "string", "Optional replacement instructions.")
                property("staged", "boolean", "Defaults to true. When true, patch requires approval.")
                required("skillId")
            },
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME,
                description = "Create a replacement version that supersedes an active skill.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("skillId", "string", "Target active skill id.")
                property("slug", "string", "Slug for replacement skill.")
                property("title", "string", "Title for replacement skill.")
                property("instructions", "string", "Instructions for replacement skill.")
                property("staged", "boolean", "Defaults to true. When true, supersede requires approval.")
                required("skillId", "slug", "title", "instructions")
            },
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME,
                description = "Archive an existing skill (staged by default).",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("skillId", "string", "Target skill id.")
                property("staged", "boolean", "Defaults to true. When true, archive requires approval.")
                required("skillId")
            },
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_APPROVE_TOOL_NAME,
                description = "Approve one pending procedural skill change.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("skillId", "string", "Pending skill id to approve.")
                property("note", "string", "Optional decision note.")
                required("skillId")
            },
            buildProceduralSkillToolDefinition(
                name = PROCEDURAL_SKILL_REJECT_TOOL_NAME,
                description = "Reject one pending procedural skill change.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("skillId", "string", "Pending skill id to reject.")
                property("note", "string", "Optional decision note.")
                required("skillId")
            },
        )

    private fun handleList(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")
        val status = proceduralSkillStatusValue(arguments, "status")
        val limit = (proceduralSkillIntValue(arguments, "limit") ?: 50).coerceIn(1, 1000)
        val rows = handlers.listSkills(status, limit, scopeKey)

        if (rows.isEmpty()) {
            return false to "No procedural skills found."
        }

        val rendered =
            rows.joinToString(separator = "\n") { row ->
                "skillId=${row.skillId} slug=${row.slug} version=${row.version} status=${row.status.name}" +
                    (row.pendingOperation?.let { op -> " pendingOperation=${op.name}" } ?: "") +
                    (row.supersedesSkillId?.let { id -> " supersedes=$id" } ?: "") +
                    (row.supersededBySkillId?.let { id -> " supersededBy=$id" } ?: "")
            }
        return false to rendered
    }

    private fun handleCreate(arguments: JsonObject): Pair<Boolean, String> {
        val slug =
            proceduralSkillStringValue(arguments, "slug")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: slug"
        val title =
            proceduralSkillStringValue(arguments, "title")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: title"
        val instructions =
            proceduralSkillStringValue(arguments, "instructions")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: instructions"
        val staged = proceduralSkillBooleanValue(arguments, "staged") ?: true
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")

        val created =
            handlers.createSkill(
                ProceduralSkillCreateRequest(slug = slug, title = title, instructions = instructions, staged = staged),
                scopeKey,
            )
        return false to "Created procedural skill ${created.skillId} with status ${created.status.name}."
    }

    private fun handlePatch(arguments: JsonObject): Pair<Boolean, String> {
        val skillId =
            proceduralSkillStringValue(arguments, "skillId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: skillId"
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")
        val request =
            ProceduralSkillPatchRequest(
                title = proceduralSkillStringValue(arguments, "title"),
                instructions = proceduralSkillStringValue(arguments, "instructions"),
                staged = proceduralSkillBooleanValue(arguments, "staged") ?: true,
            )
        val updated = handlers.patchSkill(skillId, request, scopeKey) ?: return true to "Procedural skill not found or not patchable."
        return false to "Patched procedural skill ${updated.skillId} with status ${updated.status.name}."
    }

    private fun handleSupersede(arguments: JsonObject): Pair<Boolean, String> {
        val skillId =
            proceduralSkillStringValue(arguments, "skillId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: skillId"
        val slug =
            proceduralSkillStringValue(arguments, "slug")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: slug"
        val title =
            proceduralSkillStringValue(arguments, "title")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: title"
        val instructions =
            proceduralSkillStringValue(arguments, "instructions")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: instructions"
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")
        val staged = proceduralSkillBooleanValue(arguments, "staged") ?: true

        val replacement =
            handlers.supersedeSkill(
                skillId,
                ProceduralSkillSupersedeRequest(slug = slug, title = title, instructions = instructions, staged = staged),
                scopeKey,
            ) ?: return true to "Procedural skill not found or not supersedable."

        return false to "Created replacement procedural skill ${replacement.skillId} with status ${replacement.status.name}."
    }

    private fun handleArchive(arguments: JsonObject): Pair<Boolean, String> {
        val skillId =
            proceduralSkillStringValue(arguments, "skillId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: skillId"
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")
        val staged = proceduralSkillBooleanValue(arguments, "staged") ?: true
        val archived = handlers.archiveSkill(skillId, staged, scopeKey) ?: return true to "Procedural skill not found or not archivable."
        return false to "Archive request applied to procedural skill ${archived.skillId} with status ${archived.status.name}."
    }

    private fun handleApprove(arguments: JsonObject): Pair<Boolean, String> {
        val skillId =
            proceduralSkillStringValue(arguments, "skillId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: skillId"
        val note = proceduralSkillStringValue(arguments, "note")
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")
        val approved = handlers.approveSkill(skillId, note, scopeKey) ?: return true to "Procedural skill not found or not pending approval."
        return false to "Approved procedural skill ${approved.skillId}."
    }

    private fun handleReject(arguments: JsonObject): Pair<Boolean, String> {
        val skillId =
            proceduralSkillStringValue(arguments, "skillId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: skillId"
        val note = proceduralSkillStringValue(arguments, "note")
        val scopeKey = proceduralSkillStringValue(arguments, "scopeKey")
        val rejected = handlers.rejectSkill(skillId, note, scopeKey) ?: return true to "Procedural skill not found or not pending approval."
        return false to "Rejected procedural skill ${rejected.skillId}."
    }
}

private fun buildProceduralSkillToolDefinition(
    name: String,
    description: String,
    schemaBuilder: (ProceduralSkillToolSchemaBuilder.() -> Unit)? = null,
): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", name)
    tool.addProperty("description", description)

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")

    val builder = ProceduralSkillToolSchemaBuilder()
    schemaBuilder?.invoke(builder)
    inputSchema.add("properties", builder.properties)
    if (builder.required.isNotEmpty()) {
        val requiredArray = JsonArray()
        builder.required.forEach { propertyName -> requiredArray.add(propertyName) }
        inputSchema.add("required", requiredArray)
    }

    tool.add("inputSchema", inputSchema)
    return tool
}

private class ProceduralSkillToolSchemaBuilder {
    val properties: JsonObject = JsonObject()
    val required = linkedSetOf<String>()

    fun property(
        name: String,
        type: String,
        description: String,
    ) {
        val property = JsonObject()
        property.addProperty("type", type)
        property.addProperty("description", description)
        properties.add(name, property)
    }

    fun required(vararg names: String) {
        names.forEach { name -> required.add(name) }
    }
}

private fun proceduralSkillStringValue(
    source: JsonObject,
    name: String,
): String? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()
}

private fun proceduralSkillIntValue(
    source: JsonObject,
    name: String,
): Int? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asInt }.getOrNull()
}

private fun proceduralSkillBooleanValue(
    source: JsonObject,
    name: String,
): Boolean? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asBoolean }.getOrNull()
}

private fun proceduralSkillStatusValue(
    source: JsonObject,
    name: String,
): ProceduralSkillStatus? {
    val raw = proceduralSkillStringValue(source, name)?.trim()?.uppercase() ?: return null
    return ProceduralSkillStatus.entries.firstOrNull { it.name == raw }
}
