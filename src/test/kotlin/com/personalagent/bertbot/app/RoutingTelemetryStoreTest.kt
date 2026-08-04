package com.personalagent.bertbot.app

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingTelemetryStoreTest {
    @Test
    fun `file routing telemetry store isolates scopes and records outcomes`() {
        val file = File.createTempFile("bertbot-routing-telemetry", ".json")
        file.delete()
        file.deleteOnExit()

        val store = FileRoutingTelemetryStore(file = file)

        store.withScope("scope-a") {
            store.recordOutcome(routeKey = "delegation:scope-a:coder", success = true)
            store.recordOutcome(routeKey = "delegation:scope-a:coder", success = false)
        }
        store.withScope("scope-b") {
            store.recordOutcome(routeKey = "delegation:scope-b:planner", success = true)
        }

        val scopeA = store.withScope("scope-a") { store.list(limit = 20) }
        val scopeB = store.withScope("scope-b") { store.list(limit = 20) }

        assertEquals(1, scopeA.size)
        assertEquals(1, scopeA.first().successCount)
        assertEquals(1, scopeA.first().failureCount)
        assertEquals(1, scopeB.size)
        assertEquals("delegation:scope-b:planner", scopeB.first().routeKey)
    }

    @Test
    fun `jdbc routing telemetry store records and clears per scope`() {
        val store =
            JdbcRoutingTelemetryStore(
                jdbcUrl = h2JdbcUrl(),
                tableName = "bertbot_routing_telemetry_snapshot",
            )

        store.withScope("scope-a") {
            store.recordOutcome(routeKey = "delegation:scope-a:coder", success = true)
            store.recordOutcome(routeKey = "delegation:scope-a:coder", success = true)
        }

        val scopeA = store.withScope("scope-a") { store.list(limit = 20) }
        assertEquals(1, scopeA.size)
        assertEquals(2, scopeA.first().successCount)

        store.withScope("scope-a") { store.clear() }
        assertTrue(store.withScope("scope-a") { store.list(limit = 20).isEmpty() })
    }

    @Test
    fun `compute routing hint enforces min sample threshold`() {
        val hint =
            computeRoutingHint(
                summary =
                    RoutingTelemetrySummary(
                        routeKey = "delegation:global:coder",
                        scopeKey = "global",
                        successCount = 2,
                        failureCount = 1,
                        lastUpdatedAt = Instant.now().toString(),
                    ),
                configuration =
                    RoutingHintRuntimeConfiguration(
                        enabled = true,
                        minSamplesPerRoute = 5,
                        maxInfluence = 0.25,
                        recencyHalfLifeHours = 72,
                    ),
            )

        assertNull(hint)
    }

    @Test
    fun `compute routing hint produces bounded influence and explainable reason`() {
        val hint =
            computeRoutingHint(
                summary =
                    RoutingTelemetrySummary(
                        routeKey = "delegation:global:coder",
                        scopeKey = "global",
                        successCount = 9,
                        failureCount = 1,
                        lastUpdatedAt = Instant.now().toString(),
                    ),
                configuration =
                    RoutingHintRuntimeConfiguration(
                        enabled = true,
                        minSamplesPerRoute = 5,
                        maxInfluence = 0.25,
                        recencyHalfLifeHours = 72,
                    ),
            )

        assertNotNull(hint)
        assertTrue(hint.score in 0.75..1.25)
        assertTrue(hint.successBias >= 0.0)
        assertTrue(hint.failureDampening >= 0.0)
        assertTrue(hint.reason.contains("sampleSize=10"))
        assertTrue(hint.reason.contains("route=delegation:global:coder"))
    }

    private fun h2JdbcUrl(): String =
        "jdbc:h2:mem:bertbot_routing_telemetry_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
}
