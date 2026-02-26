package no.nav.helsemelding.outbound.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.TransportStatus
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

interface Metrics {
    fun registerOutgoingMessageReceived()
    fun registerOutgoingMessageFailed(errorType: ErrorTypeTag)
    fun registerPostMessageDuration(durationNanos: Long)
    fun registerMessageSigningDuration(durationNanos: Long)
    fun registerOutgoingMessageProcessingDuration(durationNanos: Long)
    fun registerTransportStateDistribution(counts: Map<TransportStatus, Long>)
    fun registerAppRecStateDistribution(counts: Map<AppRecStatus?, Long>)
    fun registerMessageDeliveryStateDistribution(counts: Map<MessageDeliveryState, Long>)
}

class CustomMetrics(val registry: MeterRegistry) : Metrics {
    private val transportStateValues: Map<TransportStatus, AtomicLong> =
        TransportStatus.entries.associateWith { AtomicLong(0) }

    private val appRecStateValues: Map<AppRecStatus?, AtomicLong> = prepareAppRecStateValues()

    private val deliveryStateValues: Map<MessageDeliveryState, AtomicLong> =
        MessageDeliveryState.entries.associateWith { AtomicLong(0) }

    init {
        transportStateValues.forEach { (state, atomic) ->
            Gauge.builder("helsemelding_transport_state_distribution") { atomic.get().toDouble() }
                .description("Current number of messages in each transport state")
                .tag("state", state.name)
                .register(registry)
        }

        appRecStateValues.forEach { (state, atomic) ->
            val stateTag = state?.name ?: "null"
            Gauge.builder("helsemelding_app_rec_state_distribution") { atomic.get().toDouble() }
                .description("Current number of messages in each application recept state")
                .tag("state", stateTag)
                .register(registry)
        }

        deliveryStateValues.forEach { (state, atomic) ->
            Gauge.builder("helsemelding_message_deliver_state_distribution") { atomic.get().toDouble() }
                .description("Current number of messages in each message delivery state")
                .tag("state", state.name)
                .register(registry)
        }
    }

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

    override fun registerMessageSigningDuration(durationNanos: Long) {
        Timer.builder("helsemelding_message_signing_duration")
            .description("Time spent sending a message to the Payload Signing Service")
            .publishPercentileHistogram()
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    override fun registerOutgoingMessageProcessingDuration(durationNanos: Long) {
        Timer.builder("helsemelding_outgoing_message_processing_duration")
            .description("Time spent processing an outgoing message")
            .publishPercentileHistogram()
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    override fun registerTransportStateDistribution(counts: Map<TransportStatus, Long>) {
        log.debug { "Registering delivery state distribution" }
        transportStateValues.forEach { transportState ->
            transportState.value.set(counts[transportState.key] ?: 0L)
        }
    }

    override fun registerAppRecStateDistribution(counts: Map<AppRecStatus?, Long>) {
        log.debug { "Registering application recept state distribution" }
        appRecStateValues.forEach { appRecState ->
            appRecState.value.set(counts[appRecState.key] ?: 0L)
        }
    }

    override fun registerMessageDeliveryStateDistribution(counts: Map<MessageDeliveryState, Long>) {
        log.debug { "Registering message delivery state distribution" }
        deliveryStateValues.forEach { deliveryState ->
            deliveryState.value.set(counts[deliveryState.key] ?: 0L)
        }
    }

    private fun prepareAppRecStateValues(): Map<AppRecStatus?, AtomicLong> {
        val gauges = mutableMapOf<AppRecStatus?, AtomicLong>(
            null to AtomicLong(0)
        )

        AppRecStatus.entries.forEach { state ->
            gauges[state] = AtomicLong(0)
        }

        return gauges
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
        log.info { "helsemelding_post_message_duration metric is registered with duration: $durationNanos ns" }
    }

    override fun registerMessageSigningDuration(durationNanos: Long) {
        log.info { "helsemelding_message_signing_duration metric is registered with duration: $durationNanos ns" }
    }

    override fun registerOutgoingMessageProcessingDuration(durationNanos: Long) {
        log.info { "helsemelding_outgoing_message_processing_duration metric is registered with duration: $durationNanos ns" }
    }

    override fun registerTransportStateDistribution(counts: Map<TransportStatus, Long>) {
        counts.forEach { (state, count) ->
            log.info { "helsemelding_transport_state_distribution metric is updated for state: ${state.name} with count: $count" }
        }
    }

    override fun registerAppRecStateDistribution(counts: Map<AppRecStatus?, Long>) {
        counts.forEach { (state, count) ->
            log.info { "helsemelding_app_rec_state_distribution metric is updated for state: ${state?.name} with count: $count" }
        }
    }

    override fun registerMessageDeliveryStateDistribution(counts: Map<MessageDeliveryState, Long>) {
        counts.forEach { (state, count) ->
            log.info { "helsemelding_message_deliver_state_distribution metric is updated for state: ${state.name} with count: $count" }
        }
    }
}
