package com.personalagent.bertbot.app

internal object McpConstants {
    const val SERVER_NAME = "bertbot"
    const val SERVER_VERSION = "1.0.0"
    const val PROTOCOL_VERSION = "2024-11-05"

    const val ASK_BERTBOT_TOOL_NAME = "ask_bertbot"
    const val BERTBOT_STATUS_TOOL_NAME = "bertbot_status"
    const val WORKSPACE_LIST_DIR_TOOL_NAME = "workspace_list_dir"
    const val WORKSPACE_READ_FILE_TOOL_NAME = "workspace_read_file"
    const val WORKSPACE_SEARCH_TOOL_NAME = "workspace_search"
    const val POLYMARKET_GAMMA_TOOL_NAME = "polymarket_gamma_query"
    const val POLYMARKET_CLOB_TOOL_NAME = "polymarket_clob_query"
    const val POLYMARKET_DATA_TOOL_NAME = "polymarket_data_query"
    const val INGESTION_SET_APPROVAL_TOOL_NAME = "ingestion_set_approval"
    const val INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME = "ingestion_list_approved_sources"
    const val INGESTION_INGEST_MANUAL_TOOL_NAME = "ingestion_ingest_manual"
    const val INGESTION_CHAT_MANUAL_TOOL_NAME = "ingestion_chat_manual"

    const val WORKSPACE_ROOT_ENV_VAR = "BERTBOT_WORKSPACE_ROOT"

    val toolNames =
        McpToolNames(
            askBertBot = ASK_BERTBOT_TOOL_NAME,
            bertBotStatus = BERTBOT_STATUS_TOOL_NAME,
            workspaceListDir = WORKSPACE_LIST_DIR_TOOL_NAME,
            workspaceReadFile = WORKSPACE_READ_FILE_TOOL_NAME,
            workspaceSearch = WORKSPACE_SEARCH_TOOL_NAME,
            polymarketGamma = POLYMARKET_GAMMA_TOOL_NAME,
            polymarketClob = POLYMARKET_CLOB_TOOL_NAME,
            polymarketData = POLYMARKET_DATA_TOOL_NAME,
            ingestionSetApproval = INGESTION_SET_APPROVAL_TOOL_NAME,
            ingestionListApprovedSources = INGESTION_LIST_APPROVED_SOURCES_TOOL_NAME,
            ingestionIngestManual = INGESTION_INGEST_MANUAL_TOOL_NAME,
            ingestionChatManual = INGESTION_CHAT_MANUAL_TOOL_NAME,
        )

    val startupTools =
        listOf(
            ASK_BERTBOT_TOOL_NAME,
            BERTBOT_STATUS_TOOL_NAME,
            WORKSPACE_LIST_DIR_TOOL_NAME,
            WORKSPACE_READ_FILE_TOOL_NAME,
            WORKSPACE_SEARCH_TOOL_NAME,
            POLYMARKET_GAMMA_TOOL_NAME,
            POLYMARKET_CLOB_TOOL_NAME,
            POLYMARKET_DATA_TOOL_NAME,
        )

    val defaultStatusToolSurface =
        listOf(
            ASK_BERTBOT_TOOL_NAME,
            BERTBOT_STATUS_TOOL_NAME,
            WORKSPACE_LIST_DIR_TOOL_NAME,
            WORKSPACE_READ_FILE_TOOL_NAME,
            WORKSPACE_SEARCH_TOOL_NAME,
            POLYMARKET_GAMMA_TOOL_NAME,
            POLYMARKET_CLOB_TOOL_NAME,
            POLYMARKET_DATA_TOOL_NAME,
        )
}
