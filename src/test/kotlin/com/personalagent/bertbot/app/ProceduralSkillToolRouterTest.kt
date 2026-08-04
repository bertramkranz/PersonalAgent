package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProceduralSkillToolRouterTest {
    @Test
    fun `tool definitions expose procedural skill tools`() {
        val router = testRouter()

        val names = router.toolDefinitions().map { it.get("name").asString }.toSet()
        assertTrue(names.contains(PROCEDURAL_SKILL_LIST_TOOL_NAME))
        assertTrue(names.contains(PROCEDURAL_SKILL_CREATE_TOOL_NAME))
        assertTrue(names.contains(PROCEDURAL_SKILL_PATCH_TOOL_NAME))
        assertTrue(names.contains(PROCEDURAL_SKILL_SUPERSEDE_TOOL_NAME))
        assertTrue(names.contains(PROCEDURAL_SKILL_ARCHIVE_TOOL_NAME))
        assertTrue(names.contains(PROCEDURAL_SKILL_APPROVE_TOOL_NAME))
        assertTrue(names.contains(PROCEDURAL_SKILL_REJECT_TOOL_NAME))
    }

    @Test
    fun `create and list flow returns expected messages`() {
        val router = testRouter()

        val createResult =
            router.handle(
                PROCEDURAL_SKILL_CREATE_TOOL_NAME,
                JsonObject().apply {
                    add(
                        "arguments",
                        JsonObject().apply {
                            addProperty("slug", "incident-triage")
                            addProperty("title", "Incident Triage")
                            addProperty("instructions", "Runbook")
                            addProperty("staged", true)
                        },
                    )
                },
            )

        assertNotNull(createResult)
        assertEquals(false, createResult.first)
        assertContains(createResult.second, "Created procedural skill")

        val listResult = router.handle(PROCEDURAL_SKILL_LIST_TOOL_NAME, JsonObject().apply { add("arguments", JsonObject()) })
        assertNotNull(listResult)
        assertEquals(false, listResult.first)
        assertContains(listResult.second, "status=PENDING_APPROVAL")
    }

    @Test
    fun `approve and reject require skill id`() {
        val router = testRouter()

        val approveMissing = router.handle(PROCEDURAL_SKILL_APPROVE_TOOL_NAME, JsonObject().apply { add("arguments", JsonObject()) })
        assertNotNull(approveMissing)
        assertEquals(true, approveMissing.first)
        assertContains(approveMissing.second, "skillId")

        val rejectMissing = router.handle(PROCEDURAL_SKILL_REJECT_TOOL_NAME, JsonObject().apply { add("arguments", JsonObject()) })
        assertNotNull(rejectMissing)
        assertEquals(true, rejectMissing.first)
        assertContains(rejectMissing.second, "skillId")
    }

    @Test
    fun `unknown tool returns null`() {
        val router = testRouter()
        assertNull(router.handle("unknown", JsonObject()))
    }

    private fun testRouter(): ProceduralSkillToolRouter {
        val store =
            FileProceduralSkillStore(
                java.io.File
                    .createTempFile("bertbot-procedural-router", ".json")
                    .apply {
                        delete()
                        deleteOnExit()
                    },
            )

        return ProceduralSkillToolRouter(
            handlers =
                ProceduralSkillToolHandlers(
                    listSkills = { status, limit, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.list(status, limit)
                        }
                    },
                    createSkill = { request, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.create(request)
                        }
                    },
                    patchSkill = { skillId, request, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.patch(skillId, request)
                        }
                    },
                    supersedeSkill = { skillId, request, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.supersede(skillId, request)
                        }
                    },
                    archiveSkill = { skillId, staged, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.archive(skillId, staged)
                        }
                    },
                    approveSkill = { skillId, note, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.approve(skillId, note)
                        }
                    },
                    rejectSkill = { skillId, note, scopeKey ->
                        store.withScope(scopeKey ?: "global") {
                            store.reject(skillId, note)
                        }
                    },
                ),
        )
    }
}
