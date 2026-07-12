package com.personalagent.bertbot.app

import kotlin.test.Test
import kotlin.test.assertIs

class KoogRuntimeIntegrationTest {
    @Test
    fun `telemetry integration is created from default koog configuration`() {
        val telemetry =
            KoogRuntimeIntegrationFactory.createTelemetry(
                KoogFeatureRuntimeConfiguration(),
            )

        assertIs<OpenTelemetryRuntimeTelemetry>(telemetry)
        telemetry.close()
    }

    @Test
    fun `telemetry integration supports custom service info`() {
        val telemetry =
            KoogRuntimeIntegrationFactory.createTelemetry(
                KoogFeatureRuntimeConfiguration(
                    openTelemetryServiceName = "test-service",
                    openTelemetryServiceVersion = "0.0.1",
                ),
            )

        assertIs<OpenTelemetryRuntimeTelemetry>(telemetry)
        telemetry.close()
    }
}
