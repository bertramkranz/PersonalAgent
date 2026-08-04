package com.personalagent.bertbot.app

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProceduralSkillStoreTest {
    @Test
    fun `file procedural skill store supports staged create approve and scoped listing`() {
        val file = File.createTempFile("bertbot-procedural-skill", ".json")
        file.delete()
        file.deleteOnExit()

        val store = FileProceduralSkillStore(file = file)

        val staged =
            store.withScope("scope-a") {
                store.create(
                    ProceduralSkillCreateRequest(
                        slug = "incident-triage",
                        title = "Incident Triage",
                        instructions = "Collect evidence and summarize RCA.",
                        staged = true,
                    ),
                )
            }

        assertEquals(ProceduralSkillStatus.PENDING_APPROVAL, staged.status)

        val approved = store.withScope("scope-a") { store.approve(staged.skillId, note = "looks good") }
        assertNotNull(approved)
        assertEquals(ProceduralSkillStatus.ACTIVE, approved.status)
        assertEquals("looks good", approved.decisionNote)

        store.withScope("scope-b") {
            store.create(
                ProceduralSkillCreateRequest(
                    slug = "other",
                    title = "Other",
                    instructions = "Other instructions",
                    staged = false,
                ),
            )
        }

        val scopeA = store.withScope("scope-a") { store.list(limit = 10) }
        val scopeB = store.withScope("scope-b") { store.list(limit = 10) }

        assertEquals(1, scopeA.size)
        assertEquals(1, scopeB.size)
        assertEquals("incident-triage", scopeA.first().slug)
        assertEquals("other", scopeB.first().slug)
    }

    @Test
    fun `file procedural skill store supports staged patch and staged supersede approval flow`() {
        val file = File.createTempFile("bertbot-procedural-skill-patch", ".json")
        file.delete()
        file.deleteOnExit()

        val store = FileProceduralSkillStore(file = file)

        val base =
            store.create(
                ProceduralSkillCreateRequest(
                    slug = "delegate-escalation",
                    title = "Delegate Escalation",
                    instructions = "Initial runbook",
                    staged = false,
                ),
            )

        val patchCandidate =
            store.patch(
                base.skillId,
                ProceduralSkillPatchRequest(
                    instructions = "Updated runbook",
                    staged = true,
                ),
            )
        assertNotNull(patchCandidate)
        assertEquals(ProceduralSkillStatus.PENDING_APPROVAL, patchCandidate.status)
        assertEquals(ProceduralSkillOperation.PATCH, patchCandidate.pendingOperation)

        val patchedActive = store.approve(patchCandidate.skillId)
        assertNotNull(patchedActive)
        assertEquals(ProceduralSkillStatus.ACTIVE, patchedActive.status)

        val replacement =
            store.supersede(
                patchedActive.skillId,
                ProceduralSkillSupersedeRequest(
                    slug = "delegate-escalation-v2",
                    title = "Delegate Escalation V2",
                    instructions = "Replacement runbook",
                    staged = true,
                ),
            )
        assertNotNull(replacement)
        assertEquals(ProceduralSkillStatus.PENDING_APPROVAL, replacement.status)

        val replacementApproved = store.approve(replacement.skillId)
        assertNotNull(replacementApproved)
        assertEquals(ProceduralSkillStatus.ACTIVE, replacementApproved.status)

        val old = store.get(patchedActive.skillId)
        assertNotNull(old)
        assertEquals(ProceduralSkillStatus.SUPERSEDED, old.status)
        assertEquals(replacementApproved.skillId, old.supersededBySkillId)
    }

    @Test
    fun `jdbc procedural skill store supports staged archive reject and clear`() {
        val store =
            JdbcProceduralSkillStore(
                jdbcUrl = h2JdbcUrl(),
                tableName = "bertbot_procedural_skill_snapshot",
            )

        val base =
            store.withScope("scope-a") {
                store.create(
                    ProceduralSkillCreateRequest(
                        slug = "scheduler",
                        title = "Scheduler",
                        instructions = "Do scheduled checks",
                        staged = false,
                    ),
                )
            }

        val archivePending = store.withScope("scope-a") { store.archive(base.skillId, staged = true) }
        assertNotNull(archivePending)
        assertEquals(ProceduralSkillStatus.PENDING_APPROVAL, archivePending.status)
        assertEquals(ProceduralSkillOperation.ARCHIVE, archivePending.pendingOperation)

        val archiveRejected = store.withScope("scope-a") { store.reject(base.skillId, note = "keep active") }
        assertNotNull(archiveRejected)
        assertEquals(ProceduralSkillStatus.ACTIVE, archiveRejected.status)

        val listed = store.withScope("scope-a") { store.list(status = ProceduralSkillStatus.ACTIVE, limit = 10) }
        assertEquals(1, listed.size)

        store.withScope("scope-a") { store.clear() }
        assertTrue(store.withScope("scope-a") { store.list(limit = 10).isEmpty() })
        assertNull(store.withScope("scope-a") { store.get(base.skillId) })
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_procedural_skill_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
