package no.nav.helsemelding.state.service

import arrow.core.raise.recover
import arrow.fx.coroutines.parMap
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.StatusInfo
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

    suspend fun pollMessages() {
        log.info { "=== Poll cycle start ===" }
        val cycleStart = System.currentTimeMillis()

        messageStateService
            .findPollableMessages()
            .withLogging()
            .takeIf { it.isNotEmpty() }
            ?.chunked(pollerConfig.batchSize)
            ?.forEach { processBatch(it) }

        log.info { "=== Poll cycle end: ${System.currentTimeMillis() - cycleStart}ms ===" }
    }

    private suspend fun processBatch(batch: List<MessageState>) {
        val summary = batch.batchSummary()
        log.info { "processing $summary" }

        logBatchDuration(summary) {
            batch.parMap(Dispatchers.IO) { pollAndProcessMessage(it) }

            val marked = messageStateService.markAsPolled(batch.map { it.externalRefId })
            log.debug { "markedAsPolled=$marked ($summary)" }
        }
    }

    internal suspend fun pollAndProcessMessage(message: MessageState) =
        with(stateEvaluatorService) {
            message.debug { "Fetching status from EdiAdapter" }

            ediAdapterClient.getMessageStatus(message.externalRefId)
                .onRight { statuses ->
                    message.debug { "Received ${statuses.size} statuses" }
                    processMessage(statuses, message)
                }
                .onLeft { err ->
                    message.error { "Error fetching status: $err" }
                }
        }

    private suspend fun processMessage(
        externalStatuses: List<StatusInfo>?,
        message: MessageState
    ) {
        val lastStatus = when (val lastExternal = externalStatuses?.lastOrNull()) {
            null -> {
                message.warn { "No status info returned from adapter" }
                return
            }

            else -> lastExternal
        }

        message.debug { "Processing translated status: $lastStatus" }

        val externalStatus = lastStatus.translate()
        val deliveryState = externalStatus.deliveryState
        val appRecStatus = externalStatus.appRecStatus

        message.debug { "Translated: deliveryState=$deliveryState, appRecStatus=$appRecStatus" }

        val nextState = determineNextState(message, deliveryState, appRecStatus)

        message.debug { "Determining next state: old=${message.externalDeliveryState}, new=$nextState" }

        when (nextState) {
            NEW -> message.debug { message.formatUnchanged(NEW) }
            PENDING -> pending(message, deliveryState, appRecStatus)
            COMPLETED -> completed(message, deliveryState, appRecStatus)
            REJECTED -> rejected(message, deliveryState, appRecStatus)
            INVALID -> message.error { message.formatInvalidState() }
        }
    }

    private fun determineNextState(
        message: MessageState,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?
    ): MessageDeliveryState =
        with(stateEvaluatorService) {
            recover({
                val oldState = evaluate(message)
                val newState = evaluate(externalDeliveryState, appRecStatus)

                message.debug {
                    "State evaluation: old=$oldState, new=$newState"
                }

                determineNextState(oldState, newState)
            }) { e: StateError ->
                message.error {
                    "Failed evaluating state: ${e.withMessageContext(message)}"
                }
                INVALID
            }
        }

    private suspend fun pending(
        message: MessageState,
        newState: ExternalDeliveryState,
        newAppRecStatus: AppRecStatus?
    ) {
        message.info { message.formatTransition(PENDING) }
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
        message.info { message.formatTransition(COMPLETED) }
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

        message.info { "Publishing COMPLETED notification" }
        dialogMessagePublisher.publish(message.externalRefId, newAppRecStatus!!.name)
    }

    private suspend fun rejected(
        message: MessageState,
        newState: ExternalDeliveryState,
        newAppRecStatus: AppRecStatus?
    ) {
        message.warn { message.formatTransition(REJECTED) }
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

        message.warn { "Publishing REJECTED notification" }
        dialogMessagePublisher.publish(message.externalRefId, "")
    }

    private fun List<MessageState>.withLogging(): List<MessageState> = also {
        log.info { "Pollable messages size=$size" }
    }

    private inline fun MessageState.debug(crossinline msg: () -> String) =
        log.debug { "externalRefId=$externalRefId ${msg()}" }

    private inline fun MessageState.info(crossinline msg: () -> String) =
        log.info { "externalRefId=$externalRefId ${msg()}" }

    private inline fun MessageState.warn(crossinline msg: () -> String) =
        log.warn { "externalRefId=$externalRefId ${msg()}" }

    private inline fun MessageState.error(crossinline msg: () -> String) =
        log.error { "externalRefId=$externalRefId ${msg()}" }

    private fun List<MessageState>.batchSummary(): String =
        when (size) {
            0 -> "batchSize=0"
            1 -> "batchSize=1 externalRefId=${first().externalRefId}"
            else -> "batchSize=$size range=${first().externalRefId}..${last().externalRefId}"
        }

    private inline fun <T> logBatchDuration(summary: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            log.info { "batch completed: $summary took ${System.currentTimeMillis() - start}ms" }
        }
    }
}
