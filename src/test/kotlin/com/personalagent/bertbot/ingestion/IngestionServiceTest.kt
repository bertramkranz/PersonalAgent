package com.personalagent.bertbot.ingestion

import com.personalagent.bertbot.memory.BertBotMemory
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.UserProfileStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestionServiceTest {
    @Test
    fun `service ingests approved message and stores source metadata plus attachment refs`() {
        val episodicFile = File.createTempFile("episodic-memory", ".json")
        val profileFile = File.createTempFile("bertbot-profile", ".json")
        val consentFile = File.createTempFile("bertbot-consent", ".json")
        val sourceStateFile = File.createTempFile("bertbot-source-state", ".json")
        episodicFile.deleteOnExit()
        profileFile.deleteOnExit()
        consentFile.deleteOnExit()
        sourceStateFile.deleteOnExit()

        val episodic = EpisodicMemory(BertBotMemory(episodicFile))
        val profileStore = UserProfileStore(profileFile)
        val consentStore = FileConsentStore(consentFile)
        val sourceStateStore = FileSourceStateStore(sourceStateFile)
        val source = IngestionSource(IngestionPlatform.TELEGRAM, IngestionSourceKind.CHAT, "chat-1")
        consentStore.upsert(ApprovalRecord(source = source, scope = ApprovalScope.CHAT, approved = true))

        var summarizeTriggered = false
        val service =
            IngestionService(
                consentStore = consentStore,
                sourceStateStore = sourceStateStore,
                episodicMemory = episodic,
                semanticSummarizationTrigger = { summarizeTriggered = true },
                userProfileStore = profileStore,
            )

        val outcomes =
            service.ingest(
                listOf(
                    NormalizedIngestionMessage(
                        messageId = "m-1",
                        source = source,
                        senderId = "u-1",
                        senderDisplayName = "Bertram Kranz",
                        text = "I prefer concise updates and I like Kotlin.",
                        attachments =
                            listOf(
                                NormalizedAttachment(
                                    attachmentId = "file-1",
                                    kind = AttachmentKind.IMAGE,
                                    fileReference = "telegram-file-1",
                                    width = 1024,
                                    height = 768,
                                ),
                            ),
                    ),
                ),
            )

        assertEquals(IngestionDecision.APPROVED, outcomes.single().decision)
        assertTrue(summarizeTriggered)
        val entry = episodic.entries().last()
        assertTrue(entry.text.contains("INGESTED[telegram:chat-1]"))
        assertEquals("TELEGRAM", entry.sourceMetadata?.platform)
        assertEquals("file-1", entry.attachmentReferences.orEmpty().single().attachmentId)
        assertEquals("Bertram Kranz", profileStore.current().displayName)
        assertTrue(profileStore.current().recurringPreferences.any { it.contains("concise updates", ignoreCase = true) })
        assertTrue(profileStore.current().stableInterests.any { it.contains("kotlin", ignoreCase = true) })
        assertEquals("m-1", sourceStateStore.find(source)?.cursor)
    }

    @Test
    fun `service skips unapproved source and does not write memory`() {
        val episodicFile = File.createTempFile("episodic-memory", ".json")
        val profileFile = File.createTempFile("bertbot-profile", ".json")
        val consentFile = File.createTempFile("bertbot-consent", ".json")
        val sourceStateFile = File.createTempFile("bertbot-source-state", ".json")
        episodicFile.deleteOnExit()
        profileFile.deleteOnExit()
        consentFile.deleteOnExit()
        sourceStateFile.deleteOnExit()

        val source = IngestionSource(IngestionPlatform.SLACK, IngestionSourceKind.CHANNEL, "C123", "T001")
        val service =
            IngestionService(
                consentStore = FileConsentStore(consentFile),
                sourceStateStore = FileSourceStateStore(sourceStateFile),
                episodicMemory = EpisodicMemory(BertBotMemory(episodicFile)),
                semanticSummarizationTrigger = {},
                userProfileStore = UserProfileStore(profileFile),
            )

        val outcome =
            service.ingest(
                listOf(
                    NormalizedIngestionMessage(
                        messageId = "1700000000.001",
                        source = source,
                        text = "hello",
                    ),
                ),
            ).single()

        assertEquals(IngestionDecision.SKIPPED_UNAPPROVED, outcome.decision)
    }
}
