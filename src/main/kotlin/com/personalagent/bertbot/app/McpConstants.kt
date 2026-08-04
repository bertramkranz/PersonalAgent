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
    const val CHECKPOINT_LIST_TOOL_NAME = "checkpoint_list"
    const val CHECKPOINT_LATEST_TOOL_NAME = "checkpoint_latest"
    const val CHECKPOINT_GET_TOOL_NAME = "checkpoint_get"
    const val CHECKPOINT_ROLLBACK_TOOL_NAME = "checkpoint_rollback"
    const val CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME = "checkpoint_rollback_latest"
    const val CHECKPOINT_POLICY_TOOL_NAME = "checkpoint_policy"
    const val SESSION_HISTORY_LIST_TOOL_NAME = "session_history_list"
    const val SESSION_HISTORY_SEARCH_TOOL_NAME = "session_history_search"
    const val SESSION_HISTORY_CLEAR_TOOL_NAME = "session_history_clear"
    const val LEARNING_REVIEW_LIST_TOOL_NAME = "learning_review_list_pending"
    const val LEARNING_REVIEW_APPROVE_TOOL_NAME = "learning_review_approve"
    const val LEARNING_REVIEW_REJECT_TOOL_NAME = "learning_review_reject"
    const val PROCEDURAL_SKILL_LIST_TOOL_NAME = "procedural_skill_list"
    const val PROCEDURAL_SKILL_CREATE_TOOL_NAME = "procedural_skill_create"
    const val PROCEDURAL_SKILL_PATCH_TOOL_NAME = "procedural_skill_patch"
    const val PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME = "procedural_skill_supersede"
    const val PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME = "procedural_skill_archive"
    const val PROCEDURAL_SKILL_APPROVE_TOOL_NAME = "procedural_skill_approve"
    const val PROCEDURAL_SKILL_REJECT_TOOL_NAME = "procedural_skill_reject"
    const val SCHEDULED_JOB_LIST_TOOL_NAME = "scheduled_job_list"
    const val SCHEDULED_JOB_CREATE_TOOL_NAME = "scheduled_job_create"
    const val SCHEDULED_JOB_UPDATE_TOOL_NAME = "scheduled_job_update"
    const val SCHEDULED_JOB_PAUSE_TOOL_NAME = "scheduled_job_pause"
    const val SCHEDULED_JOB_RESUME_TOOL_NAME = "scheduled_job_resume"
    const val SCHEDULED_JOB_RUN_TOOL_NAME = "scheduled_job_run"
    const val SCHEDULED_JOB_REMOVE_TOOL_NAME = "scheduled_job_remove"
    const val SCHEDULED_JOB_HISTORY_TOOL_NAME = "scheduled_job_history"

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
            checkpointList = CHECKPOINT_LIST_TOOL_NAME,
            checkpointLatest = CHECKPOINT_LATEST_TOOL_NAME,
            checkpointGet = CHECKPOINT_GET_TOOL_NAME,
            checkpointRollback = CHECKPOINT_ROLLBACK_TOOL_NAME,
            checkpointRollbackLatest = CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME,
            checkpointPolicy = CHECKPOINT_POLICY_TOOL_NAME,
            sessionHistoryList = SESSION_HISTORY_LIST_TOOL_NAME,
            sessionHistorySearch = SESSION_HISTORY_SEARCH_TOOL_NAME,
            sessionHistoryClear = SESSION_HISTORY_CLEAR_TOOL_NAME,
            learningReviewListPending = LEARNING_REVIEW_LIST_TOOL_NAME,
            learningReviewApprove = LEARNING_REVIEW_APPROVE_TOOL_NAME,
            learningReviewReject = LEARNING_REVIEW_REJECT_TOOL_NAME,
            proceduralSkillList = PROCEDURAL_SKILL_LIST_TOOL_NAME,
            proceduralSkillCreate = PROCEDURAL_SKILL_CREATE_TOOL_NAME,
            proceduralSkillPatch = PROCEDURAL_SKILL_PATCH_TOOL_NAME,
            proceduralSkillSupersede = PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME,
            proceduralSkillArchive = PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME,
            proceduralSkillApprove = PROCEDURAL_SKILL_APPROVE_TOOL_NAME,
            proceduralSkillReject = PROCEDURAL_SKILL_REJECT_TOOL_NAME,
            scheduledJobList = SCHEDULED_JOB_LIST_TOOL_NAME,
            scheduledJobCreate = SCHEDULED_JOB_CREATE_TOOL_NAME,
            scheduledJobUpdate = SCHEDULED_JOB_UPDATE_TOOL_NAME,
            scheduledJobPause = SCHEDULED_JOB_PAUSE_TOOL_NAME,
            scheduledJobResume = SCHEDULED_JOB_RESUME_TOOL_NAME,
            scheduledJobRun = SCHEDULED_JOB_RUN_TOOL_NAME,
            scheduledJobRemove = SCHEDULED_JOB_REMOVE_TOOL_NAME,
            scheduledJobHistory = SCHEDULED_JOB_HISTORY_TOOL_NAME,
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
            CHECKPOINT_LIST_TOOL_NAME,
            CHECKPOINT_LATEST_TOOL_NAME,
            CHECKPOINT_GET_TOOL_NAME,
            CHECKPOINT_ROLLBACK_TOOL_NAME,
            CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME,
            CHECKPOINT_POLICY_TOOL_NAME,
            SESSION_HISTORY_LIST_TOOL_NAME,
            SESSION_HISTORY_SEARCH_TOOL_NAME,
            SESSION_HISTORY_CLEAR_TOOL_NAME,
            LEARNING_REVIEW_LIST_TOOL_NAME,
            LEARNING_REVIEW_APPROVE_TOOL_NAME,
            LEARNING_REVIEW_REJECT_TOOL_NAME,
            PROCEDURAL_SKILL_LIST_TOOL_NAME,
            PROCEDURAL_SKILL_CREATE_TOOL_NAME,
            PROCEDURAL_SKILL_PATCH_TOOL_NAME,
            PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME,
            PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME,
            PROCEDURAL_SKILL_APPROVE_TOOL_NAME,
            PROCEDURAL_SKILL_REJECT_TOOL_NAME,
            SCHEDULED_JOB_LIST_TOOL_NAME,
            SCHEDULED_JOB_CREATE_TOOL_NAME,
            SCHEDULED_JOB_UPDATE_TOOL_NAME,
            SCHEDULED_JOB_PAUSE_TOOL_NAME,
            SCHEDULED_JOB_RESUME_TOOL_NAME,
            SCHEDULED_JOB_RUN_TOOL_NAME,
            SCHEDULED_JOB_REMOVE_TOOL_NAME,
            SCHEDULED_JOB_HISTORY_TOOL_NAME,
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
            CHECKPOINT_LIST_TOOL_NAME,
            CHECKPOINT_LATEST_TOOL_NAME,
            CHECKPOINT_GET_TOOL_NAME,
            CHECKPOINT_ROLLBACK_TOOL_NAME,
            CHECKPOINT_ROLLBACK_LATEST_TOOL_NAME,
            CHECKPOINT_POLICY_TOOL_NAME,
            SESSION_HISTORY_LIST_TOOL_NAME,
            SESSION_HISTORY_SEARCH_TOOL_NAME,
            SESSION_HISTORY_CLEAR_TOOL_NAME,
            LEARNING_REVIEW_LIST_TOOL_NAME,
            LEARNING_REVIEW_APPROVE_TOOL_NAME,
            LEARNING_REVIEW_REJECT_TOOL_NAME,
            PROCEDURAL_SKILL_LIST_TOOL_NAME,
            PROCEDURAL_SKILL_CREATE_TOOL_NAME,
            PROCEDURAL_SKILL_PATCH_TOOL_NAME,
            PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME,
            PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME,
            PROCEDURAL_SKILL_APPROVE_TOOL_NAME,
            PROCEDURAL_SKILL_REJECT_TOOL_NAME,
            SCHEDULED_JOB_LIST_TOOL_NAME,
            SCHEDULED_JOB_CREATE_TOOL_NAME,
            SCHEDULED_JOB_UPDATE_TOOL_NAME,
            SCHEDULED_JOB_PAUSE_TOOL_NAME,
            SCHEDULED_JOB_RESUME_TOOL_NAME,
            SCHEDULED_JOB_RUN_TOOL_NAME,
            SCHEDULED_JOB_REMOVE_TOOL_NAME,
            SCHEDULED_JOB_HISTORY_TOOL_NAME,
        )
}
