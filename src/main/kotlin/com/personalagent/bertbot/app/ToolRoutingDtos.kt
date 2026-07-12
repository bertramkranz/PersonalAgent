package com.personalagent.bertbot.app

import com.google.gson.JsonObject

internal data class ToolInvocationRequestDto(
    val toolName: String?,
    val arguments: JsonObject,
)

internal object ToolInvocationRequestMapper {
    fun from(
        toolName: String?,
        params: JsonObject,
        fallbackToSelfWhenMissingArguments: Boolean = false,
    ): ToolInvocationRequestDto =
        ToolInvocationRequestDto(
            toolName = toolName,
            arguments =
                params.objectValue("arguments")
                    ?: if (fallbackToSelfWhenMissingArguments) params else JsonObject(),
        )
}
