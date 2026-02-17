package no.nav.helsemelding.state.service

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.recover
import arrow.core.right
import arrow.fx.coroutines.parMap
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.Dispatchers
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.ediadapter.model.StatusInfo
import no.nav.helsemelding.state.EdiAdapterError.FetchFailure
import no.nav.helsemelding.state.EdiAdapterError.NoApprecReturned
import no.nav.helsemelding.state.PublishError
import no.nav.helsemelding.state.StateError
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.config
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.ApprecStatusMessage
import no.nav.helsemelding.state.model.DeliveryEvaluationState
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.MessageState
import no.nav.helsemelding.state.model.NextStateDecision
import no.nav.helsemelding.state.model.NextStateDecision.Rejected.AppRec
import no.nav.helsemelding.state.model.NextStateDecision.Rejected.Transport
import no.nav.helsemelding.state.model.TransportStatusMessage
import no.nav.helsemelding.state.model.UpdateState
import no.nav.helsemelding.state.model.formatExternal
import no.nav.helsemelding.state.model.formatInvalidState
import no.nav.helsemelding.state.model.formatNew
import no.nav.helsemelding.state.model.formatTransition
import no.nav.helsemelding.state.model.formatUnchanged
import no.nav.helsemelding.state.model.logPrefix
import no.nav.helsemelding.state.model.toJson
import no.nav.helsemelding.state.publisher.StatusMessagePublisher
import no.nav.helsemelding.state.util.translate
import no.nav.helsemelding.state.util.withSpan
import no.nav.helsemelding.state.withMessageContext
import org.apache.kafka.clients.producer.RecordMetadata
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("PollerService")

class PollerService(
    private val ediAdapterClient: EdiAdapterClient,
    private val messageStateService: MessageStateService,
    private val stateEvaluatorService: StateEvaluatorService,
    private val statusMessagePublisher: StatusMessagePublisher
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
        log.info { "Processing ($summary)" }

        logBatchDuration(summary) {
            batch.parMap(Dispatchers.IO) { pollAndProcessMessage(it) }

            val marked = messageStateService.markAsPolled(batch.map { it.externalRefId })
            log.debug { "Marked as polled (count=$marked, $summary)" }
        }
    }

    internal suspend fun pollAndProcessMessage(message: MessageState): Either<ErrorMessage, List<StatusInfo>> {
        return tracer.withSpan("Poll and process message") {
            log.debug { "${message.logPrefix()} Fetching status from EDI Adapter" }

            ediAdapterClient.getMessageStatus(message.externalRefId)
                .onRight { statuses ->
                    log.debug { "${message.logPrefix()} Received ${statuses.size} statuses" }
                    processMessage(statuses, message)
                }
                .onLeft { error -> log.error { "${message.logPrefix()} Error fetching status: $error" } }
        }
    }

    private suspend fun processMessage(
        externalStatuses: List<StatusInfo>?,
        message: MessageState
    ) {
        val lastStatus = when (val lastExternal = externalStatuses?.lastOrNull()) {
            null -> {
                log.warn { "${message.logPrefix()} No status info returned from EDI Adapter" }
                return
            }

            else -> lastExternal
        }

        log.debug { "${message.logPrefix()} Processing translated status: $lastStatus" }

        val externalStatus = lastStatus.translate()
        val deliveryState = externalStatus.deliveryState
        val appRecStatus = externalStatus.appRecStatus

        log.debug { message.formatExternal(deliveryState, appRecStatus) }

        val nextDecision = determineNextState(message, deliveryState, appRecStatus)

        log.debug { "${message.logPrefix()} Next state decision: $nextDecision" }

        when (nextDecision) {
            NextStateDecision.Unchanged -> log.debug { message.formatUnchanged() }
            is NextStateDecision.Transition ->
                when (nextDecision.to) {
                    NEW -> log.debug { message.formatNew() }
                    PENDING -> pending(message, deliveryState, appRecStatus)
                    COMPLETED -> completed(message, deliveryState, appRecStatus)
                    INVALID -> log.error { message.formatInvalidState() }
                    REJECTED -> error("Use NextStateDecision.Rejected.Transport/AppRec instead of Transition(REJECTED)")
                }

            Transport -> rejected(message, deliveryState, Transport)
            AppRec -> rejected(message, deliveryState, AppRec)
        }
    }

    private fun determineNextState(
        message: MessageState,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?
    ): NextStateDecision =
        with(stateEvaluatorService) {
            recover({
                val old = evaluate(message)
                val new = evaluate(externalDeliveryState, appRecStatus)

                determineNextState(old, new).withLogging(message, old, new)
            }) { e: StateTransitionError ->
                log.error { "Failed evaluating state: ${e.withMessageContext(message)}" }
                NextStateDecision.Transition(INVALID)
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
                message.externalRefId,
                message.messageType,
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
                message.externalRefId,
                message.messageType,
                message.externalDeliveryState,
                newState,
                message.appRecStatus,
                newAppRecStatus
            )
        )

        publishApprecStatus(message)
    }

    private suspend fun rejected(
        message: MessageState,
        externalDeliveryState: ExternalDeliveryState,
        rejected: NextStateDecision.Rejected
    ) {
        log.warn { message.formatTransition(REJECTED) }

        messageStateService.recordStateChange(
            UpdateState(
                message.externalRefId,
                message.messageType,
                message.externalDeliveryState,
                externalDeliveryState,
                message.appRecStatus,
                message.appRecStatus
            )
        )

        when (rejected) {
            AppRec -> publishApprecStatus(message)
            Transport -> publishTransportStatus(message.id, externalDeliveryState)
        }
    }

    private suspend fun publishApprecStatus(message: MessageState): Either<StateError, RecordMetadata> =
        either {
            val apprecInfo = fetchApprecInfo(message).bind()
            publishApprecStatus(message.id, apprecInfo).bind()
        }
            .onLeft { error ->
                when (error) {
                    is PublishError.Failure ->
                        log.error(error.cause) { error.withMessageContext(message) }

                    else -> log.error { error.withMessageContext(message) }
                }
            }

    private suspend fun fetchApprecInfo(
        message: MessageState
    ): Either<StateError, ApprecInfo> =
        ediAdapterClient.getApprecInfo(message.externalRefId)
            .mapLeft { FetchFailure(message.externalRefId, it) }
            .flatMap { apprecs ->
                apprecs.lastOrNull()
                    ?.right()
                    ?: NoApprecReturned(message.externalRefId).left()
            }

    private suspend fun publishApprecStatus(
        messageId: Uuid,
        apprecInfo: ApprecInfo
    ): Either<PublishError, RecordMetadata> =
        statusMessagePublisher.publish(
            messageId,
            apprecStatusMessage(
                messageId,
                apprecInfo
            )
                .toJson()
        )
            .withLogging(messageId)

    private suspend fun publishTransportStatus(
        messageId: Uuid,
        deliveryState: ExternalDeliveryState
    ): Either<PublishError, RecordMetadata> =
        statusMessagePublisher.publish(
            messageId,
            transportStatusMessage(
                messageId,
                deliveryState
            )
                .toJson()
        )
            .withLogging(messageId)

    private fun transportStatusMessage(
        messageId: Uuid,
        deliveryState: ExternalDeliveryState
    ): TransportStatusMessage =
        TransportStatusMessage(
            messageId = messageId,
            timestamp = Clock.System.now(),
            error = TransportStatusMessage.TransportError(
                code = deliveryState.name,
                details = "Transport failed for messageId=$messageId:  ${deliveryState.name}"
            )
        )

    private fun apprecStatusMessage(
        messageId: Uuid,
        apprecInfo: ApprecInfo
    ): ApprecStatusMessage =
        ApprecStatusMessage(
            messageId = messageId,
            timestamp = Clock.System.now(),
            apprec = apprecInfo
        )

    private fun NextStateDecision.withLogging(
        message: MessageState,
        oldEvaluationState: DeliveryEvaluationState,
        newEvaluationState: DeliveryEvaluationState
    ): NextStateDecision = also { nextState ->
        log.debug {
            "${message.logPrefix()} Evaluated state: " +
                "old=(transport=${oldEvaluationState.transport}, appRec=${oldEvaluationState.appRec}), " +
                "new=(transport=${newEvaluationState.transport}, appRec=${newEvaluationState.appRec}), " +
                "next=$nextState"
        }
    }

    private fun Either<PublishError, RecordMetadata>.withLogging(
        messageId: Uuid
    ): Either<PublishError, RecordMetadata> = also { either ->
        either.fold(
            { log.error { "Publish failed: messageId=$messageId, error=$it" } },
            { log.info { "Publish succeeded: messageId=$messageId, topic=${it.topic()}" } }
        )
    }

    private fun List<MessageState>.withLogging(): List<MessageState> = also {
        log.info { "Pollable messages size=$size" }
    }

    private fun List<MessageState>.batchSummary(): String =
        when (size) {
            0 -> "batchSize=0"
            1 -> "batchSize=1 externalRefId=${first().externalRefId}"
            else -> "batchSize=$size first=${first().externalRefId} last=${last().externalRefId}"
        }

    private inline fun <T> logBatchDuration(summary: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            log.info { "Batch completed ($summary took ${System.currentTimeMillis() - start}ms)" }
        }
    }
}
