package no.nav.helsemelding.outbound.plugin

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.MeterRegistry

fun Application.configureMetrics(prometheusRegistry: MeterRegistry) {
    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }
}
