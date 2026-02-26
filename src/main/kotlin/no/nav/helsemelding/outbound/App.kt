package no.nav.helsemelding.outbound

import arrow.continuations.SuspendApp
import arrow.continuations.ktor.server
import arrow.core.raise.result
import arrow.fx.coroutines.resourceScope
import arrow.resilience.Schedule
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.netty.Netty
import io.ktor.utils.io.CancellationException
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import no.nav.helsemelding.outbound.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.outbound.metrics.CustomMetrics
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.plugin.configureMetrics
import no.nav.helsemelding.outbound.plugin.configureRoutes
import no.nav.helsemelding.outbound.processor.MessageProcessor
import no.nav.helsemelding.outbound.publisher.statusMessagePublisher
import no.nav.helsemelding.outbound.receiver.MessageReceiver
import no.nav.helsemelding.outbound.repository.ExposedMessageRepository
import no.nav.helsemelding.outbound.repository.ExposedMessageStateHistoryRepository
import no.nav.helsemelding.outbound.repository.ExposedMessageStateTransactionRepository
import no.nav.helsemelding.outbound.service.MessageStateService
import no.nav.helsemelding.outbound.service.PollerService
import no.nav.helsemelding.outbound.service.StateEvaluatorService
import no.nav.helsemelding.outbound.service.TransactionalMessageStateService
import no.nav.helsemelding.outbound.util.coroutineScope
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

fun main() = SuspendApp {
    result {
        resourceScope {
            val deps = dependencies()
            val metrics = CustomMetrics(deps.meterRegistry)

            val scope = coroutineScope(coroutineContext)

            val poller = PollerService(
                deps.ediAdapterClient,
                messageStateService(deps.database),
                stateEvaluatorService(),
                statusMessagePublisher(deps.kafkaPublisher)
            )

            val messageProcessor = MessageProcessor(
                messageReceiver = messageReceiver(deps.kafkaReceiver, metrics),
                messageStateService = messageStateService(deps.database),
                ediAdapterClient = deps.ediAdapterClient,
                payloadSigningClient = deps.payloadSigningClient,
                metrics = metrics
            )

            server(
                Netty,
                port = config().server.port.value,
                preWait = config().server.preWait,
                module = stateServiceModule(deps.meterRegistry)
            )

            messageProcessor.processMessages(scope)

            scope.launch { schedulePoller(poller) }

            scope.launch {
                scheduleMetricsRefreshing(
                    messageStateService(deps.database),
                    metrics
                )
            }

            awaitCancellation()
        }
    }
        .onFailure { error -> if (error !is CancellationException) logError(error) }
}

internal fun stateServiceModule(
    meterRegistry: PrometheusMeterRegistry
): Application.() -> Unit {
    return {
        configureMetrics(meterRegistry)
        configureRoutes(meterRegistry)
    }
}

private suspend fun schedulePoller(pollerService: PollerService): Long {
    return Schedule
        .spaced<Unit>(config().poller.scheduleInterval)
        .repeat { pollerService.pollMessages() }
}

private suspend fun scheduleMetricsRefreshing(
    messageStateService: MessageStateService,
    metrics: Metrics
): Long {
    return Schedule
        .spaced<Unit>(config().metrics.metricsUpdatingInterval)
        .repeat {
            refreshMetrics(messageStateService, metrics)
        }
}

private suspend fun refreshMetrics(messageStateService: MessageStateService, metrics: Metrics) {
    val transportStateCounts = messageStateService.countByTransportState()
    metrics.registerTransportStateDistribution(transportStateCounts)

    val appRecStateCounts = messageStateService.countByAppRecState()
    metrics.registerAppRecStateDistribution(appRecStateCounts)

    val deliveryStateCounts = messageStateService.countMessageDeliveryState()
    metrics.registerMessageDeliveryStateDistribution(deliveryStateCounts)
}

private fun logError(t: Throwable) = log.error { "Shutdown state-service due to: ${t.stackTraceToString()}" }

private fun stateEvaluatorService(): StateEvaluatorService =
    StateEvaluatorService(
        TransportStatusTranslator(),
        StateTransitionEvaluator(
            TransportTransitionEvaluator(),
            AppRecTransitionEvaluator()
        )

    )

private fun messageStateService(database: Database): MessageStateService {
    val messageRepository = ExposedMessageRepository(database)
    val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

    val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
        database,
        messageRepository,
        messageStateHistoryRepository
    )
    return TransactionalMessageStateService(
        messageRepository,
        messageStateHistoryRepository,
        messageStateTransactionRepository
    )
}

private fun messageReceiver(
    kafkaReceiver: KafkaReceiver<String, ByteArray>,
    metrics: Metrics
): MessageReceiver =
    MessageReceiver(config().kafka.topics.dialogMessageOut, kafkaReceiver, metrics)
