package no.nav.emottak.state.service

import arrow.core.raise.recover
import arrow.fx.coroutines.parMap
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import no.nav.emottak.state.StateError
import no.nav.emottak.state.adapter.EdiAdapterClient
import no.nav.emottak.state.config
import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.MessageDeliveryState
import no.nav.emottak.state.model.MessageDeliveryState.COMPLETED
import no.nav.emottak.state.model.MessageDeliveryState.INVALID
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageDeliveryState.PENDING
import no.nav.emottak.state.model.MessageDeliveryState.REJECTED
import no.nav.emottak.state.model.MessageState
import no.nav.emottak.state.model.UpdateState
import no.nav.emottak.state.model.formatInvalidState
import no.nav.emottak.state.model.formatTransition
import no.nav.emottak.state.model.formatUnchanged
import no.nav.emottak.state.publisher.MessagePublisher
import no.nav.emottak.state.withMessageContext

private val log = KotlinLogging.logger {}

class PollerService(
    private val ediAdapterClient: EdiAdapterClient,
    private val messageStateService: MessageStateService,
    private val stateEvaluatorService: StateEvaluatorService,
    private val dialogMessagePublisher: MessagePublisher
) {
    private val pollerConfig = config().poller

    fun pollMessages(scope: CoroutineScope): Job =
        pollableMessages()
            .chunked(pollerConfig.fetchLimit)
            .parMap { currentBatch ->
                currentBatch.parMap(Dispatchers.IO) { pollAndProcessMessage(it) }
                val marked = messageStateService.markAsPolled(currentBatch.map { it.externalRefId })
                log.debug { "$marked messages marked as polled" }
            }
            .launchIn(scope)

    internal suspend fun pollAndProcessMessage(message: MessageState) = with(stateEvaluatorService) {
        val externalRefId = message.externalRefId
        val (externalStatuses, error) = ediAdapterClient.getMessageStatus(externalRefId)
        println("Statuses: $externalStatuses")
        val externalStatus = externalStatuses!!.last()

        val nextState = determineNextState(message, externalStatus.deliveryState, externalStatus.appRecStatus)
        when (nextState) {
            NEW -> log.debug { message.formatUnchanged(NEW) }
            PENDING -> pending(message, externalStatus.deliveryState, externalStatus.appRecStatus)
            COMPLETED -> completed(message, externalStatus.deliveryState, externalStatus.appRecStatus)
            REJECTED -> rejected(message, externalStatus.deliveryState, externalStatus.appRecStatus)
            INVALID -> log.error { message.formatInvalidState() }
        }
    }

    private fun pollableMessages(): Flow<MessageState> =
        flow { emit(messageStateService.findPollableMessages()) }
            .flatMapConcat { currentBatch ->
                if (currentBatch.isEmpty()) {
                    emptyFlow()
                } else {
                    currentBatch.asFlow().onCompletion { emitAll(pollableMessages()) }
                }
            }

    private fun determineNextState(
        message: MessageState,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?
    ): MessageDeliveryState = with(stateEvaluatorService) {
        recover({
            val oldState = evaluate(message)
            val newState = evaluate(externalDeliveryState, appRecStatus)

            determineNextState(oldState, newState)
        }) { e: StateError ->
            log.error { e.withMessageContext(message) }
            INVALID
        }
    }

    private suspend fun pending(
        message: MessageState,
        newState: ExternalDeliveryState,
        newAppRecStatus: AppRecStatus?
    ) {
        log.info { message.formatTransition(PENDING) }
        messageStateService.recordStateChange(
            UpdateState(
                message.messageType,
                message.externalRefId,
                message.externalDeliveryState,
                newState,
                message.appRecStatus,
                newAppRecStatus
            )
        )
    }

    private suspend fun completed(
        message: MessageState,
        newState: ExternalDeliveryState,
        newAppRecStatus: AppRecStatus?
    ) {
        log.info { message.formatTransition(COMPLETED) }
        messageStateService.recordStateChange(
            UpdateState(
                message.messageType,
                message.externalRefId,
                message.externalDeliveryState,
                newState,
                message.appRecStatus,
                newAppRecStatus
            )
        )
        dialogMessagePublisher.publish(message.externalRefId, newAppRecStatus!!.name)
    }

    private suspend fun rejected(
        message: MessageState,
        newState: ExternalDeliveryState,
        newAppRecStatus: AppRecStatus?
    ) {
        log.warn { message.formatTransition(REJECTED) }
        messageStateService.recordStateChange(
            UpdateState(
                message.messageType,
                message.externalRefId,
                message.externalDeliveryState,
                newState,
                message.appRecStatus,
                newAppRecStatus
            )
        )
        // should publish rejections to its own topic!
        dialogMessagePublisher.publish(message.externalRefId, "")
    }
}
