package com.personalagent.bertbot.app

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledJobsTest {
    @Test
    fun `file scheduled job stores support lifecycle and history`() {
        val jobsFile = File.createTempFile("bertbot-scheduled-jobs", ".json")
        val historyFile = File.createTempFile("bertbot-scheduled-jobs-history", ".jsonl")
        jobsFile.delete()
        historyFile.delete()
        jobsFile.deleteOnExit()
        historyFile.deleteOnExit()

        val jobStore = FileScheduledJobStore(jobsFile)
        val historyStore = FileScheduledJobExecutionStore(historyFile)
        val service =
            ScheduledJobService(
                jobStore = jobStore,
                executionStore = historyStore,
                runJob = { ScheduledJobRunResult(success = true) },
                configuration = ScheduledJobsRuntimeConfiguration(enabled = false),
            )

        val created = service.create("scope-a", ScheduledJobCreateRequest(scheduleSeconds = 60, payload = "summarize updates"))
        assertEquals(ScheduledJobState.ACTIVE, created.state)

        val updated = service.update("scope-a", created.jobId, ScheduledJobUpdateRequest(scheduleSeconds = 120, payload = "summarize blockers"))
        assertNotNull(updated)
        assertEquals(120, updated.scheduleSeconds)

        val paused = service.pause("scope-a", created.jobId)
        assertNotNull(paused)
        assertEquals(ScheduledJobState.PAUSED, paused.state)

        val resumed = service.resume("scope-a", created.jobId)
        assertNotNull(resumed)
        assertEquals(ScheduledJobState.ACTIVE, resumed.state)

        val run = service.runNow("scope-a", created.jobId)
        assertNotNull(run)
        assertEquals(ScheduledJobRunOutcome.SUCCESS, run.outcome)

        val history = service.listHistory("scope-a", created.jobId, limit = 10)
        assertEquals(1, history.size)

        val removed = service.remove("scope-a", created.jobId)
        assertTrue(removed)
        assertTrue(service.list("scope-a", limit = 10).isEmpty())
    }

    @Test
    fun `jdbc scheduled job stores persist and isolate scopes`() {
        val stores =
            ScheduledJobStoreFactory.create(
                PersistenceRuntimeConfiguration(
                    backend = "jdbc",
                    jdbcUrl = h2JdbcUrl(),
                    scheduledJobJdbcTable = "bertbot_scheduled_job_snapshot",
                    scheduledJobExecutionJdbcTable = "bertbot_scheduled_job_execution_event",
                ),
            )
        val service =
            ScheduledJobService(
                jobStore = stores.first,
                executionStore = stores.second,
                runJob = { ScheduledJobRunResult(success = true) },
                configuration = ScheduledJobsRuntimeConfiguration(enabled = false),
            )

        val a = service.create("scope-a", ScheduledJobCreateRequest(scheduleSeconds = 60, payload = "task a"))
        service.create("scope-b", ScheduledJobCreateRequest(scheduleSeconds = 60, payload = "task b"))

        assertEquals(1, service.list("scope-a", limit = 20).size)
        assertEquals(1, service.list("scope-b", limit = 20).size)

        val run = service.runNow("scope-a", a.jobId)
        assertNotNull(run)
        assertEquals(1, service.listHistory("scope-a", a.jobId, limit = 20).size)
    }

    @Test
    fun `scheduled run updates next run and records failure outcome`() {
        val jobsFile = File.createTempFile("bertbot-scheduled-jobs", ".json")
        val historyFile = File.createTempFile("bertbot-scheduled-jobs-history", ".jsonl")
        jobsFile.delete()
        historyFile.delete()
        jobsFile.deleteOnExit()
        historyFile.deleteOnExit()

        val jobStore = FileScheduledJobStore(jobsFile)
        val historyStore = FileScheduledJobExecutionStore(historyFile)
        val service =
            ScheduledJobService(
                jobStore = jobStore,
                executionStore = historyStore,
                runJob = { ScheduledJobRunResult(success = false, errorSummary = "boom") },
                configuration = ScheduledJobsRuntimeConfiguration(enabled = false),
            )

        val created = service.create("global", ScheduledJobCreateRequest(scheduleSeconds = 60, payload = "run once"))
        val execution = service.runNow("global", created.jobId)
        assertNotNull(execution)
        assertEquals(ScheduledJobRunOutcome.FAILURE, execution.outcome)
        assertEquals("boom", execution.errorSummary)

        val updated = service.list("global", limit = 10).firstOrNull { it.jobId == created.jobId }
        assertNotNull(updated)
        assertEquals(ScheduledJobRunOutcome.FAILURE, updated.lastOutcome)
        assertNotNull(updated.lastRunAt)
        assertTrue(Instant.parse(updated.nextRunAt).isAfter(Instant.parse(updated.lastRunAt)))
    }

    @Test
    fun `runNow returns null for unknown jobs`() {
        val jobsFile = File.createTempFile("bertbot-scheduled-jobs", ".json")
        val historyFile = File.createTempFile("bertbot-scheduled-jobs-history", ".jsonl")
        jobsFile.delete()
        historyFile.delete()
        jobsFile.deleteOnExit()
        historyFile.deleteOnExit()

        val service =
            ScheduledJobService(
                jobStore = FileScheduledJobStore(jobsFile),
                executionStore = FileScheduledJobExecutionStore(historyFile),
                runJob = { ScheduledJobRunResult(success = true) },
                configuration = ScheduledJobsRuntimeConfiguration(enabled = false),
            )

        assertNull(service.runNow("global", "missing-job"))
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_scheduled_jobs_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
