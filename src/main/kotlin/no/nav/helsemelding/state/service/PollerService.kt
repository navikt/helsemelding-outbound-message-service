package no.nav.helsemelding.state.service

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
import no.nav.emottak.ediadapter.client.EdiAdapterClient
import no.nav.emottak.ediadapter.model.StatusInfo
import no.nav.helsemelding.state.StateError
import no.nav.helsemelding.state.config
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.MessageState
import no.nav.helsemelding.state.model.UpdateState
import no.nav.helsemelding.state.model.formatInvalidState
import no.nav.helsemelding.state.model.formatTransition
import no.nav.helsemelding.state.model.formatUnchanged
import no.nav.helsemelding.state.publisher.MessagePublisher
import no.nav.helsemelding.state.util.translate
import no.nav.helsemelding.state.withMessageContext

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

        when (error) {
            null -> processMessage(externalStatuses, message)
            else -> log.error { error }
        }
    }

    private suspend fun processMessage(
        externalStatuses: List<StatusInfo>?,
        message: MessageState
    ) {
        val externalStatus = externalStatuses!!.last().translate()

        val deliveryState = externalStatus.deliveryState
        val appRecStatus = externalStatus.appRecStatus

        val nextState = determineNextState(message, deliveryState, appRecStatus)
        when (nextState) {
            NEW -> log.debug { message.formatUnchanged(NEW) }
            PENDING -> pending(message, deliveryState, appRecStatus)
            COMPLETED -> completed(message, deliveryState, appRecStatus)
            REJECTED -> rejected(message, deliveryState, appRecStatus)
            INVALID -> log.error { message.formatInvalidState() }
        }
    }

    private fun pollableMessages(): Flow<MessageState> =
        flow { emit(messageStateService.findPollableMessages().withLogging()) }
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

    private fun List<MessageState>.withLogging(): List<MessageState> {
        log.info { "Polling messages: found $size pollable messages" }
        return this
    }
}
