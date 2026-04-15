package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.recover
import arrow.core.right
import arrow.fx.coroutines.parMap
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.Dispatchers
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import no.nav.helsemelding.ediadapter.model.StatusInfo
import no.nav.helsemelding.outbound.EdiAdapterError.FetchFailure
import no.nav.helsemelding.outbound.EdiAdapterError.NoApprecReturned
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.StateError
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecErrorMessage
import no.nav.helsemelding.outbound.model.AppRecPayload
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.DeliveryEvaluationState
import no.nav.helsemelding.outbound.model.ErrorPayload
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.outbound.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.outbound.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.outbound.model.MessageDeliveryState.NEW
import no.nav.helsemelding.outbound.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.outbound.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import no.nav.helsemelding.outbound.model.NextStateDecision
import no.nav.helsemelding.outbound.model.NextStateDecision.Rejected
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.model.formatExternal
import no.nav.helsemelding.outbound.model.formatInvalidState
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
import kotlin.time.TimeSource
import kotlin.time.measureTime
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

        val duration = measureTime {
            messageStateService
                .findPollableMessages()
                .withLogging()
                .takeIf { it.isNotEmpty() }
                ?.chunked(pollerConfig.batchSize)
                ?.parMap(Dispatchers.IO) { batch -> processBatch(batch) }
        }

        log.info { "=== Poll cycle end: ${duration.inWholeMilliseconds}ms ===" }
    }

    private suspend fun processBatch(batch: List<MessageState>) {
        val summary = batch.batchSummary()
        log.info { "Processing ($summary)" }

        logBatchDuration(summary) {
            batch.forEach { pollAndProcessMessage(it) }

            val marked = messageStateService.markAsPolled(batch.map { it.externalRefId })
            log.debug { "Marked as polled (count=$marked, $summary)" }
        }
    }

    internal suspend fun pollAndProcessMessage(message: MessageState) {
        tracer.withSpan("Poll and process message") {
            log.debug { "${message.logPrefix()} Fetching status from EDI Adapter" }

            ediAdapterClient.getMessageStatus(message.externalRefId)
                .onRight { statuses ->
                    log.debug { "${message.logPrefix()} Received ${statuses.size} statuses" }
                    processMessage(statuses, message)
                }
                .onLeft { error ->
                    log.error { "${message.logPrefix()} Error fetching status: $error" }
                }
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
            NextStateDecision.Unchanged -> {
                log.debug { message.formatUnchanged() }
            }

            else -> {
                logTransition(message, decision)
                recordStateChange(message, external)

                val apprecInfo =
                    if (decision.requiresApprecInfo()) {
                        fetchApprecInfoOrNull(message)
                    } else {
                        null
                    }

                publishStatusMessage(message, external, decision, apprecInfo)
                    .onLeft { error ->
                        when (error) {
                            is PublishError.Failure ->
                                log.error(error.cause) { error.withMessageContext(message) }
                        }
                    }
            }
        }
    }

    private fun logTransition(
        message: MessageState,
        decision: NextStateDecision
    ) {
        when (decision) {
            Rejected.AppRec,
            Rejected.Transport -> log.warn { message.formatTransition(decision) }

            is NextStateDecision.Transition -> when (decision.to) {
                INVALID -> log.error { message.formatInvalidState() }
                else -> log.info { message.formatTransition(decision) }
            }

            is NextStateDecision.Pending -> log.info { message.formatTransition(decision) }
            NextStateDecision.Unchanged -> Unit
        }
    }

    private suspend fun recordStateChange(
        message: MessageState,
        external: ExternalStatus
    ) {
        messageStateService.recordStateChange(
            UpdateState(
                externalRefId = message.externalRefId,
                messageType = message.messageType,
                oldDeliveryState = message.externalDeliveryState,
                newDeliveryState = external.deliveryState,
                oldAppRecStatus = message.appRecStatus,
                newAppRecStatus = external.appRecStatus
            )
        )
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

    private suspend fun publishStatusMessage(
        message: MessageState,
        external: ExternalStatus,
        decision: NextStateDecision,
        apprecInfo: ApprecInfo?
    ): Either<PublishError, RecordMetadata> =
        statusMessagePublisher.publish(
            message.id,
            statusMessage(message, external, decision, apprecInfo).toJson()
        ).withLogging(message.id)

    private fun statusMessage(
        message: MessageState,
        external: ExternalStatus,
        decision: NextStateDecision,
        apprecInfo: ApprecInfo?
    ): MessageStatusEvent =
        MessageStatusEvent(
            messageId = message.id,
            timestamp = Clock.System.now(),
            status = decision.toMessageStatus(external),
            apprec = apprecInfo?.toPayload(),
            error = decision.toErrorPayload(message.id)
        )

    private fun NextStateDecision.toMessageStatus(
        external: ExternalStatus
    ): MessageStatus = when (this) {
        NextStateDecision.Unchanged ->
            error("No status message should be published for Unchanged")

        is NextStateDecision.Pending ->
            external.toPendingStatus()

        is NextStateDecision.Transition -> when (to) {
            NEW -> MessageStatus.NEW
            COMPLETED -> MessageStatus.COMPLETED
            INVALID -> MessageStatus.INVALID
            PENDING -> external.toPendingStatus()
            REJECTED -> error("Use Rejected decision variants")
        }

        Rejected.AppRec -> MessageStatus.REJECTED_APPREC
        Rejected.Transport -> MessageStatus.REJECTED_TRANSPORT
    }

    private fun ExternalStatus.toPendingStatus(): MessageStatus =
        if (deliveryState == ACKNOWLEDGED) {
            MessageStatus.PENDING_APPREC
        } else {
            MessageStatus.PENDING_TRANSPORT
        }

    private fun NextStateDecision.requiresApprecInfo(): Boolean = when (this) {
        is NextStateDecision.Transition -> to == COMPLETED
        Rejected.AppRec -> true
        else -> false
    }

    private suspend fun fetchApprecInfoOrNull(message: MessageState): ApprecInfo? =
        fetchApprecInfo(message)
            .onLeft { error ->
                log.warn { error.withMessageContext(message) }
            }
            .getOrNull()

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

    private fun ApprecInfo.toPayload(): AppRecPayload =
        AppRecPayload(
            receiverHerId = receiverHerId,
            appRecStatus = appRecStatus?.name,
            appRecErrorList = appRecErrorList.orEmpty().map {
                AppRecErrorMessage(
                    code = it.errorCode,
                    text = it.description
                )
            }
        )

    private fun NextStateDecision.toErrorPayload(messageId: Uuid): ErrorPayload? = when (this) {
        Rejected.Transport -> ErrorPayload(
            code = "REJECTED_TRANSPORT",
            details = "Transport failed for messageId=$messageId"
        )

        is NextStateDecision.Transition ->
            if (to == INVALID) {
                ErrorPayload(
                    code = "INVALID_STATE",
                    details = "Unable to evaluate next state for messageId=$messageId"
                )
            } else {
                null
            }

        else -> null
    }

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
        val mark = TimeSource.Monotonic.markNow()
        return try {
            block()
        } finally {
            log.info { "Batch completed ($summary took ${mark.elapsedNow().inWholeMilliseconds}ms)" }
        }
    }
}
