package no.nav.helsemelding.state.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

interface Metrics {
    fun registerOutgoingMessageReceived()
    fun registerOutgoingMessageFailed(errorType: ErrorTypeTag)
    fun registerPostMessageDuration(durationNanos: Long)
}

class CustomMetrics(val registry: MeterRegistry) : Metrics {
    override fun registerOutgoingMessageReceived() {
        Counter.builder("helsemelding_outgoing_messages_received")
            .description("Number of outgoing messages received from Kafka")
            .register(registry)
            .increment()
    }

    override fun registerOutgoingMessageFailed(errorType: ErrorTypeTag) {
        Counter.builder("helsemelding_outgoing_messages_failed")
            .description("Number of outgoing messages that failed to be processed")
            .tag("error_type", errorType.value)
            .register(registry)
            .increment()
    }

    override fun registerPostMessageDuration(durationNanos: Long) {
        Timer.builder("helsemelding_post_message_duration")
            .description("Time spent posting a message to EDI Adapter")
            .publishPercentileHistogram()
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }
}

class FakeMetrics() : Metrics {
    override fun registerOutgoingMessageReceived() {
        log.info { "helsemelding_outgoing_messages_received metric is registered" }
    }

    override fun registerOutgoingMessageFailed(errorType: ErrorTypeTag) {
        log.info { "helsemelding_outgoing_messages_failed metric is registered with error_type: ${errorType.value}" }
    }

    override fun registerPostMessageDuration(durationNanos: Long) {
        log.info { "helsemelding_post_message_duration metric is registered with duration: $durationNanos nanoseconds" }
    }
}
