package no.nav.helsemelding.outbound

import arrow.continuations.SuspendApp
import arrow.continuations.ktor.server
import arrow.core.raise.Raise
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
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.v3.MshConfiguration
import no.nav.helsemelding.ediadapter.model.v3.ReceiveNotificationChannel.API
import no.nav.helsemelding.ediadapter.model.v3.SetMshConfigurationsRequest
import no.nav.helsemelding.outbound.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.outbound.metrics.CustomMetrics
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.plugin.configureMetrics
import no.nav.helsemelding.outbound.plugin.configureRoutes
import no.nav.helsemelding.outbound.processor.MessageProcessor
import no.nav.helsemelding.outbound.publisher.StatusMessagePublisher
import no.nav.helsemelding.outbound.receiver.MessageReceiver
import no.nav.helsemelding.outbound.repository.ExposedMessageRepository
import no.nav.helsemelding.outbound.repository.ExposedMessageStateHistoryRepository
import no.nav.helsemelding.outbound.repository.ExposedMessageStateTransactionRepository
import no.nav.helsemelding.outbound.repository.ExposedNotificationOffsetRepository
import no.nav.helsemelding.outbound.service.MessageLifecycleOrchestratorService
import no.nav.helsemelding.outbound.service.MessageStateService
import no.nav.helsemelding.outbound.service.MetricsService
import no.nav.helsemelding.outbound.service.NotificationService
import no.nav.helsemelding.outbound.service.PrometheusMetricsService
import no.nav.helsemelding.outbound.service.StateEvaluatorService
import no.nav.helsemelding.outbound.service.TransactionalMessageStateService
import no.nav.helsemelding.outbound.util.coroutineScope
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

fun main() = SuspendApp {
    result {
        resourceScope {
            val deps = dependencies()

            setMshConfiguration(
                deps.ediAdapterClient,
                config().ediAdapter.senderHerId.value
            )

            val metrics = CustomMetrics(deps.meterRegistry)

            val notificationService = NotificationService(
                deps.ediAdapterClient,
                messageStateService(deps.database),
                stateEvaluatorService(),
                StatusMessagePublisher(config().kafka.topics, deps.kafkaPublisher),
                ExposedNotificationOffsetRepository(deps.database)
            )

            val messageLifecycleService = MessageLifecycleOrchestratorService(
                messageStateService = messageStateService(deps.database),
                ediAdapterClient = deps.ediAdapterClient,
                metrics = metrics
            )

            val messageProcessor = MessageProcessor(
                messageReceiver = messageReceiver(deps.kafkaReceiver),
                messageLifecycleService = messageLifecycleService,
                metrics = metrics
            )

            server(
                Netty,
                port = config().server.port.value,
                preWait = config().server.preWait,
                module = stateServiceModule(deps.meterRegistry)
            )

            val scope = coroutineScope(coroutineContext)

            messageProcessor.processMessages(scope)
            notificationService.processNotifications(scope)
            scope.launch {
                scheduleMetricsRefreshing(
                    metricsService(deps.database),
                    metrics
                )
            }

            awaitCancellation()
        }
    }
        .onFailure { e -> if (e !is CancellationException) log.error(e) { "Shutdown outbound message service" } }
}

internal fun stateServiceModule(
    meterRegistry: PrometheusMeterRegistry
): Application.() -> Unit = {
    configureMetrics(meterRegistry)
    configureRoutes(meterRegistry)
}

private suspend fun Raise<Throwable>.setMshConfiguration(ediAdapterClient: EdiAdapterClient, senderHerId: Int) {
    ediAdapterClient.setMshConfigurations(
        SetMshConfigurationsRequest(
            listOf(
                MshConfiguration(
                    herId = senderHerId,
                    receiveNotificationChannel = API
                )
            )
        )
    )
        .mapLeft { error -> MshConfigurationException("Unable to configure MSH: $error") }
        .bind()

    log.info { "MSH configured for herId: $senderHerId (notificationChannel: $API)" }
}

private suspend fun scheduleMetricsRefreshing(
    metricsService: MetricsService,
    metrics: Metrics
): Long {
    return Schedule
        .spaced<Unit>(config().metrics.metricsUpdatingInterval)
        .repeat { refreshMetrics(metricsService, metrics) }
}

private suspend fun refreshMetrics(metricsService: MetricsService, metrics: Metrics) {
    val transportStateCounts = metricsService.countByTransportState()
    metrics.registerTransportStateDistribution(transportStateCounts)

    val appRecStateCounts = metricsService.countByAppRecState()
    metrics.registerAppRecStateDistribution(appRecStateCounts)

    val deliveryStateCounts = metricsService.countByMessageDeliveryState()
    metrics.registerMessageDeliveryStateDistribution(deliveryStateCounts)
}

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

private fun metricsService(database: Database): MetricsService {
    val messageRepository = ExposedMessageRepository(database)
    return PrometheusMetricsService(messageRepository)
}

private fun messageReceiver(
    kafkaReceiver: KafkaReceiver<String, ByteArray>
): MessageReceiver =
    MessageReceiver(
        config().kafka.topics.dialogMessageOut,
        kafkaReceiver
    )

private class MshConfigurationException(message: String) : RuntimeException(message)
