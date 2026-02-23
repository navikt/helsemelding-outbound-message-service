package no.nav.helsemelding.outbound.service

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
import no.nav.helsemelding.outbound.EdiAdapterError.FetchFailure
import no.nav.helsemelding.outbound.EdiAdapterError.NoApprecReturned
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.StateError
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.ApprecStatusMessage
import no.nav.helsemelding.outbound.model.DeliveryEvaluationState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.outbound.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.outbound.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.outbound.model.MessageDeliveryState.NEW
import no.nav.helsemelding.outbound.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.outbound.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.NextStateDecision
import no.nav.helsemelding.outbound.model.NextStateDecision.Rejected
import no.nav.helsemelding.outbound.model.TransportStatusMessage
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.model.formatExternal
import no.nav.helsemelding.outbound.model.formatInvalidState
import no.nav.helsemelding.outbound.model.formatNew
import no.nav.helsemelding.outbound.model.formatTransition
import no.nav.helsemelding.outbound.model.formatUnchanged
import no.nav.helsemelding.outbound.model.logPrefix
import no.nav.helsemelding.outbound.model.toJson
import no.nav.helsemelding.outbound.publisher.StatusMessagePublisher
import no.nav.helsemelding.outbound.util.translate
import no.nav.helsemelding.outbound.util.withSpan
import no.nav.helsemelding.outbound.withMessageContext
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
        val lastStatus = externalStatuses?.lastOrNull() ?: run {
            log.warn { "${message.logPrefix()} No status info returned from EDI Adapter" }
            return
        }

        log.debug { "${message.logPrefix()} Processing translated status: $lastStatus" }

        val external = lastStatus.translate()
        log.debug { message.formatExternal(external.deliveryState, external.appRecStatus) }

        val decision = determineNextState(message, external.deliveryState, external.appRecStatus)
        log.debug { "${message.logPrefix()} Next state decision: $decision" }

        handleDecision(message, external, decision)
    }

    private suspend fun handleDecision(
        message: MessageState,
        external: ExternalStatus,
        decision: NextStateDecision
    ) {
        when (decision) {
            NextStateDecision.Unchanged -> onUnchanged(message)
            is NextStateDecision.Transition -> onTransition(message, external, decision)
            is NextStateDecision.Pending -> onPending(message, external, decision)
            Rejected.AppRec -> onRejectedAppRec(message, external)
            Rejected.Transport -> onRejectedTransport(message, external)
        }
    }

    private fun onUnchanged(message: MessageState) = log.debug { message.formatUnchanged() }

    private suspend fun onTransition(
        message: MessageState,
        external: ExternalStatus,
        decision: NextStateDecision.Transition
    ) {
        when (decision.to) {
            NEW -> log.debug { message.formatNew() }
            COMPLETED -> completed(message, external.deliveryState, external.appRecStatus)
            INVALID -> log.error { message.formatInvalidState() }
            PENDING -> error("Use NextStateDecision.Pending")
            REJECTED -> error("Use NextStateDecision.Rejected")
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

    private suspend fun onPending(
        message: MessageState,
        external: ExternalStatus,
        decision: NextStateDecision.Pending
    ) {
        log.info { message.formatTransition(decision) }
        pending(message, external.deliveryState, external.appRecStatus)
    }

    private suspend fun onRejectedAppRec(message: MessageState, external: ExternalStatus) {
        log.warn { message.formatTransition(Rejected.AppRec) }
        rejected(message, external.deliveryState, external.appRecStatus)
        publishApprecStatus(message)
    }

    private suspend fun onRejectedTransport(message: MessageState, external: ExternalStatus) {
        log.warn { message.formatTransition(Rejected.Transport) }
        rejected(message, external.deliveryState, external.appRecStatus)
        publishTransportStatus(message.id, external.deliveryState)
    }

    private suspend fun pending(
        message: MessageState,
        newState: ExternalDeliveryState,
        newAppRecStatus: AppRecStatus?
    ) {
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
        newAppRecStatus: AppRecStatus?
    ) {
        messageStateService.recordStateChange(
            UpdateState(
                message.externalRefId,
                message.messageType,
                message.externalDeliveryState,
                externalDeliveryState,
                message.appRecStatus,
                newAppRecStatus
            )
        )
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
