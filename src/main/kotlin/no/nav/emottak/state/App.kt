package no.nav.emottak.state

import arrow.continuations.SuspendApp
import arrow.continuations.ktor.server
import arrow.core.raise.result
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import arrow.resilience.Schedule
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.netty.Netty
import io.ktor.utils.io.CancellationException
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import no.nav.emottak.state.adapter.FakeEdiAdapterClient
import no.nav.emottak.state.evaluator.StateEvaluator
import no.nav.emottak.state.evaluator.StateTransitionValidator
import no.nav.emottak.state.plugin.configureMetrics
import no.nav.emottak.state.plugin.configureRoutes
import no.nav.emottak.state.publisher.DialogMessagePublisher
import no.nav.emottak.state.repository.ExposedMessageRepository
import no.nav.emottak.state.repository.ExposedMessageStateHistoryRepository
import no.nav.emottak.state.repository.ExposedMessageStateTransactionRepository
import no.nav.emottak.state.service.MessageStateService
import no.nav.emottak.state.service.PollerService
import no.nav.emottak.state.service.StateEvaluatorService
import no.nav.emottak.state.service.TransactionalMessageStateService
import no.nav.emottak.utils.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

fun main() = SuspendApp {
    result {
        resourceScope {
            val deps = dependencies()

            val poller = PollerService(
                FakeEdiAdapterClient(),
                messageStateService(deps.database),
                stateEvaluatorService(),
                dialogMessagePublisher(deps.kafkaPublisher)
            )

            server(
                Netty,
                port = config().server.port.value,
                preWait = config().server.preWait,
                module = stateServiceModule(deps.meterRegistry)
            )

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
