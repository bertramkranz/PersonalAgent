package com.personalagent.bertbot.ingestion.connectors

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalChatAsyncRunnerTest {
    @Test
    fun `managed runner executes submitted work before close`() {
        val runner = ManagedExternalChatAsyncRunner()
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)

        runner.submit {
            counter.incrementAndGet()
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, counter.get())
        runner.close()
    }

    @Test
    fun `managed runner closes without throwing and rejects new work execution`() {
        val runner = ManagedExternalChatAsyncRunner()
        runner.close()

        val latch = CountDownLatch(1)
        runner.submit {
            latch.countDown()
        }

        assertFalse(latch.await(300, TimeUnit.MILLISECONDS))
    }
}
