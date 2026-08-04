package com.personalagent.bertbot.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val SCHEDULED_JOB_LIST_TOOL_NAME = "scheduled_job_list"
internal const val SCHEDULED_JOB_CREATE_TOOL_NAME = "scheduled_job_create"
internal const val SCHEDULED_JOB_UPDATE_TOOL_NAME = "scheduled_job_update"
internal const val SCHEDULED_JOB_PAUSE_TOOL_NAME = "scheduled_job_pause"
internal const val SCHEDULED_JOB_RESUME_TOOL_NAME = "scheduled_job_resume"
internal const val SCHEDULED_JOB_RUN_TOOL_NAME = "scheduled_job_run"
internal const val SCHEDULED_JOB_REMOVE_TOOL_NAME = "scheduled_job_remove"
internal const val SCHEDULED_JOB_HISTORY_TOOL_NAME = "scheduled_job_history"

internal data class ScheduledJobToolHandlers(
    val listJobs: (limit: Int, scopeKey: String?) -> List<ScheduledJob>,
    val createJob: (scheduleSeconds: Long, payload: String, scopeKey: String?) -> ScheduledJob?,
    val updateJob: (jobId: String, scheduleSeconds: Long?, payload: String?, scopeKey: String?) -> ScheduledJob?,
    val pauseJob: (jobId: String, scopeKey: String?) -> ScheduledJob?,
    val resumeJob: (jobId: String, scopeKey: String?) -> ScheduledJob?,
    val runJob: (jobId: String, scopeKey: String?) -> ScheduledJobExecution?,
    val removeJob: (jobId: String, scopeKey: String?) -> Boolean,
    val listHistory: (jobId: String?, limit: Int, scopeKey: String?) -> List<ScheduledJobExecution>,
)

internal class ScheduledJobToolRouter(
    private val handlers: ScheduledJobToolHandlers,
) : ToolRouter {
    override val id: String = "scheduled_jobs"

    override fun handle(
        toolName: String?,
        params: JsonObject,
    ): Pair<Boolean, String>? {
        val request = ToolInvocationRequestMapper.from(toolName, params)
        val args = request.arguments

        return when (request.toolName) {
            SCHEDULED_JOB_LIST_TOOL_NAME -> handleList(args)
            SCHEDULED_JOB_CREATE_TOOL_NAME -> handleCreate(args)
            SCHEDULED_JOB_UPDATE_TOOL_NAME -> handleUpdate(args)
            SCHEDULED_JOB_PAUSE_TOOL_NAME -> handlePause(args)
            SCHEDULED_JOB_RESUME_TOOL_NAME -> handleResume(args)
            SCHEDULED_JOB_RUN_TOOL_NAME -> handleRun(args)
            SCHEDULED_JOB_REMOVE_TOOL_NAME -> handleRemove(args)
            SCHEDULED_JOB_HISTORY_TOOL_NAME -> handleHistory(args)
            else -> null
        }
    }

    override fun toolDefinitions(): List<JsonObject> =
        listOf(
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_LIST_TOOL_NAME,
                description = "List scheduled jobs for a scope.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("limit", "number", "Maximum jobs to return (default 50, max 1000).")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_CREATE_TOOL_NAME,
                description = "Create a scheduled job for a scope.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("scheduleSeconds", "number", "Schedule interval in seconds (minimum 30).")
                property("payload", "string", "Prompt payload to execute when job runs.")
                required("scheduleSeconds", "payload")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_UPDATE_TOOL_NAME,
                description = "Update schedule or payload for one job.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("jobId", "string", "Scheduled job id.")
                property("scheduleSeconds", "number", "Optional new schedule interval in seconds.")
                property("payload", "string", "Optional new prompt payload.")
                required("jobId")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_PAUSE_TOOL_NAME,
                description = "Pause one scheduled job.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("jobId", "string", "Scheduled job id.")
                required("jobId")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_RESUME_TOOL_NAME,
                description = "Resume one paused scheduled job.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("jobId", "string", "Scheduled job id.")
                required("jobId")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_RUN_TOOL_NAME,
                description = "Run one scheduled job immediately.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("jobId", "string", "Scheduled job id.")
                required("jobId")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_REMOVE_TOOL_NAME,
                description = "Remove one scheduled job.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("jobId", "string", "Scheduled job id.")
                required("jobId")
            },
            buildScheduledJobToolDefinition(
                name = SCHEDULED_JOB_HISTORY_TOOL_NAME,
                description = "List scheduled-job execution history.",
            ) {
                property("scopeKey", "string", "Optional persistence scope key. Uses current scope when omitted.")
                property("jobId", "string", "Optional job id filter.")
                property("limit", "number", "Maximum rows to return (default 50, max 1000).")
            },
        )

    private fun handleList(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val limit = (scheduledJobIntValue(arguments, "limit") ?: 50).coerceIn(1, 1000)
        val jobs = handlers.listJobs(limit, scopeKey)
        if (jobs.isEmpty()) {
            return false to "No scheduled jobs found."
        }

        val rendered =
            jobs.joinToString(separator = "\n") { job ->
                "jobId=${job.jobId} state=${job.state.name} scheduleSeconds=${job.scheduleSeconds} nextRunAt=${job.nextRunAt}"
            }
        return false to rendered
    }

    private fun handleCreate(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val scheduleSeconds =
            scheduledJobLongValue(arguments, "scheduleSeconds")
                ?: return true to "Missing required field: scheduleSeconds"
        val payload =
            scheduledJobStringValue(arguments, "payload")
                ?: return true to "Missing required field: payload"

        val created =
            handlers.createJob(scheduleSeconds, payload, scopeKey)
                ?: return true to "Failed to create scheduled job."
        return false to "Created scheduled job ${created.jobId}."
    }

    private fun handleUpdate(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val jobId =
            scheduledJobStringValue(arguments, "jobId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: jobId"
        val scheduleSeconds = scheduledJobLongValue(arguments, "scheduleSeconds")
        val payload = scheduledJobStringValue(arguments, "payload")
        val updated =
            handlers.updateJob(jobId, scheduleSeconds, payload, scopeKey)
                ?: return true to "Scheduled job not found."
        return false to "Updated scheduled job ${updated.jobId}."
    }

    private fun handlePause(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val jobId =
            scheduledJobStringValue(arguments, "jobId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: jobId"
        val paused = handlers.pauseJob(jobId, scopeKey) ?: return true to "Scheduled job not found."
        return false to "Paused scheduled job ${paused.jobId}."
    }

    private fun handleResume(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val jobId =
            scheduledJobStringValue(arguments, "jobId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: jobId"
        val resumed = handlers.resumeJob(jobId, scopeKey) ?: return true to "Scheduled job not found."
        return false to "Resumed scheduled job ${resumed.jobId}."
    }

    private fun handleRun(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val jobId =
            scheduledJobStringValue(arguments, "jobId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: jobId"
        val execution = handlers.runJob(jobId, scopeKey) ?: return true to "Scheduled job not found."
        return false to "Ran scheduled job ${execution.jobId} runId=${execution.runId} outcome=${execution.outcome.name}."
    }

    private fun handleRemove(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val jobId =
            scheduledJobStringValue(arguments, "jobId")?.takeIf { it.isNotBlank() }
                ?: return true to "Missing required field: jobId"
        val removed = handlers.removeJob(jobId, scopeKey)
        if (!removed) {
            return true to "Scheduled job not found."
        }
        return false to "Removed scheduled job $jobId."
    }

    private fun handleHistory(arguments: JsonObject): Pair<Boolean, String> {
        val scopeKey = scheduledJobStringValue(arguments, "scopeKey")
        val jobId = scheduledJobStringValue(arguments, "jobId")?.takeIf { it.isNotBlank() }
        val limit = (scheduledJobIntValue(arguments, "limit") ?: 50).coerceIn(1, 1000)
        val entries = handlers.listHistory(jobId, limit, scopeKey)
        if (entries.isEmpty()) {
            return false to "No scheduled job history found."
        }

        val rendered =
            entries.joinToString(separator = "\n") { entry ->
                "runId=${entry.runId} jobId=${entry.jobId} outcome=${entry.outcome.name} trigger=${entry.trigger.name} startedAt=${entry.startedAt}"
            }
        return false to rendered
    }
}

private fun buildScheduledJobToolDefinition(
    name: String,
    description: String,
    schemaBuilder: (ScheduledJobToolSchemaBuilder.() -> Unit)? = null,
): JsonObject {
    val tool = JsonObject()
    tool.addProperty("name", name)
    tool.addProperty("description", description)

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")

    val builder = ScheduledJobToolSchemaBuilder()
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

private class ScheduledJobToolSchemaBuilder {
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

private fun scheduledJobStringValue(
    source: JsonObject,
    name: String,
): String? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asString }.getOrNull()
}

private fun scheduledJobIntValue(
    source: JsonObject,
    name: String,
): Int? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asInt }.getOrNull()
}

private fun scheduledJobLongValue(
    source: JsonObject,
    name: String,
): Long? {
    val element = source.get(name) ?: return null
    if (!element.isJsonPrimitive) {
        return null
    }
    return runCatching { element.asLong }.getOrNull()
}
