package no.nav.helsemelding.state

import arrow.continuations.SuspendApp
import arrow.continuations.ktor.server
import arrow.core.raise.result
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import arrow.resilience.Schedule
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.netty.Netty
import io.ktor.utils.io.CancellationException
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import no.nav.helsemelding.state.evaluator.StateEvaluator
import no.nav.helsemelding.state.evaluator.StateTransitionValidator
import no.nav.helsemelding.state.plugin.configureMetrics
import no.nav.helsemelding.state.plugin.configureRoutes
import no.nav.helsemelding.state.processor.MessageProcessor
import no.nav.helsemelding.state.publisher.DialogMessagePublisher
import no.nav.helsemelding.state.receiver.MessageReceiver
import no.nav.helsemelding.state.repository.ExposedMessageRepository
import no.nav.helsemelding.state.repository.ExposedMessageStateHistoryRepository
import no.nav.helsemelding.state.repository.ExposedMessageStateTransactionRepository
import no.nav.helsemelding.state.service.MessageStateService
import no.nav.helsemelding.state.service.PollerService
import no.nav.helsemelding.state.service.StateEvaluatorService
import no.nav.helsemelding.state.service.TransactionalMessageStateService
import no.nav.helsemelding.state.util.coroutineScope
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

fun main() = SuspendApp {
    result {
        resourceScope {
            val deps = dependencies()

            val scope = coroutineScope(coroutineContext)

            val poller = PollerService(
                deps.ediAdapterClient,
                messageStateService(deps.database),
                stateEvaluatorService(),
                dialogMessagePublisher(deps.kafkaPublisher)
            )

            val messageProcessor = MessageProcessor(
                messageReceiver = messageReceiver(deps.kafkaReceiver),
                messageStateService = messageStateService(deps.database),
                ediAdapterClient = deps.ediAdapterClient
            )

            server(
                Netty,
                port = config().server.port.value,
                preWait = config().server.preWait,
                module = stateServiceModule(deps.meterRegistry)
            )

            messageProcessor.processMessages(scope)

            schedulePoller(poller)

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

private suspend fun ResourceScope.schedulePoller(pollerService: PollerService): Long {
    val scope = coroutineScope(currentCoroutineContext())
    return Schedule
        .spaced<Unit>(config().poller.scheduleInterval)
        .repeat { pollerService.pollMessages(scope) }
}

private fun logError(t: Throwable) = log.error { "Shutdown state-service due to: ${t.stackTraceToString()}" }

private fun stateEvaluatorService(): StateEvaluatorService =
    StateEvaluatorService(
        StateEvaluator(),
        StateTransitionValidator()
    )

private fun dialogMessagePublisher(kafkaPublisher: KafkaPublisher<String, ByteArray>): DialogMessagePublisher =
    DialogMessagePublisher(kafkaPublisher)

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

private fun messageReceiver(kafkaReceiver: KafkaReceiver<String, ByteArray>): MessageReceiver =
    MessageReceiver(kafkaReceiver)
