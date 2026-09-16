package no.nav.helsemelding.outbound.service

import arrow.core.getOrElse
import arrow.core.raise.recover
import arrow.fx.coroutines.parMap
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.Dispatchers
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.v3.StatusInfo
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecPayload
import no.nav.helsemelding.outbound.model.ErrorPayload
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
import no.nav.helsemelding.outbound.publisher.MessagePublisher
import no.nav.helsemelding.outbound.util.translate
import no.nav.helsemelding.outbound.util.withSpan
import no.nav.helsemelding.outbound.withMessageContext
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
    private val statusMessagePublisher: MessagePublisher
) {
    private val pollerConfig = config().poller

    suspend fun pollMessages() {
        log.info { "=== Poll cycle start ===" }

        val duration = measureTime {
            messageStateService
                .findPollableMessages()
                .also { log.info { "Pollable messages size=${it.size}" } }
                .chunked(pollerConfig.batchSize)
                .parMap(Dispatchers.IO) { batch -> processBatch(batch) }
        }

        log.info { "=== Poll cycle end: ${duration.inWholeMilliseconds}ms ===" }
    }

    private suspend fun processBatch(batch: List<MessageState>) {
        val summary = batch.batchSummary()
        log.info { "Processing ($summary)" }

        logBatchDuration(summary) {
            batch.forEach { pollAndProcessMessage(it) }

            messageStateService.markAsPolled(batch.map { it.externalRefId })
                .also { log.debug { "Marked as polled (count=$it, $summary)" } }
        }
    }

    internal suspend fun pollAndProcessMessage(message: MessageState) {
        tracer.withSpan("Poll and process message") {
            fetchExternalStatus(message)?.let { processStatus(message, it) }
        }
    }

    private suspend fun processStatus(message: MessageState, external: ExternalStatus) {
        val decision = determineNextState(message, external)
            .also { logTransition(message, it) }
        when (decision) {
            NextStateDecision.Unchanged -> Unit
            else -> {
                recordStateChange(message, external)
                val statusEvent = decision.toStatusEvent(message.id, external.apprec)
                statusMessagePublisher.publish(statusEvent)
                    .onLeft { logPublishError(message, it) }
            }
        }
    }

    private suspend fun fetchExternalStatus(message: MessageState): ExternalStatus? {
        log.debug { "${message.logPrefix()} Fetching status from EDI Adapter" }
        val response = ediAdapterClient.getMessageStatus(message.externalRefId)
            .getOrElse { error ->
                log.error { "${message.logPrefix()} Error fetching status: $error" }
                return null
            }
        return response.statusList.orEmpty()
            .also { log.debug { "${message.logPrefix()} Received ${it.size} statuses" } }
            .singleReceiverStatus(message)
            ?.translate()
            ?.also { log.debug { message.formatExternal(it.deliveryState, it.appRecStatus) } }
    }

    private fun List<StatusInfo>.singleReceiverStatus(message: MessageState): StatusInfo? =
        singleOrNull().also {
            if (it == null) {
                log.warn { "${message.logPrefix()} Expected exactly one receiver status from EDI Adapter (count=$size)" }
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
        external: ExternalStatus
    ): NextStateDecision =
        with(stateEvaluatorService) {
            recover({
                val old = evaluate(message)
                val new = evaluate(external.deliveryState, external.appRecStatus)

                determineNextState(old, new).also { decision ->
                    log.debug {
                        "${message.logPrefix()} Evaluated state: " +
                            "old=(transport=${old.transport}, appRec=${old.appRec}), " +
                            "new=(transport=${new.transport}, appRec=${new.appRec}), " +
                            "next=$decision"
                    }
                }
            }) { e: StateTransitionError ->
                log.error { "Failed evaluating state: ${e.withMessageContext(message)}" }
                NextStateDecision.Transition(INVALID)
            }
        }

    private fun NextStateDecision.toStatusEvent(messageId: Uuid, apprec: AppRecPayload?): MessageStatusEvent =
        MessageStatusEvent(
            messageId = messageId,
            timestamp = Clock.System.now(),
            status = toMessageStatus(),
            apprec = apprec,
            error = toErrorPayload(messageId)
        )

    private fun NextStateDecision.toMessageStatus(): MessageStatus = when (this) {
        NextStateDecision.Unchanged -> error("No status message should be published for Unchanged")

        is NextStateDecision.Transition -> when (to) {
            NEW -> MessageStatus.NEW
            COMPLETED -> MessageStatus.COMPLETED
            INVALID -> MessageStatus.INVALID
            PENDING -> error("Use explicit Pending variants")
            REJECTED -> error("Use explicit Rejected variants")
        }

        NextStateDecision.Pending.Transport -> MessageStatus.PENDING_TRANSPORT
        NextStateDecision.Pending.AppRec -> MessageStatus.PENDING_APPREC

        Rejected.Transport -> MessageStatus.REJECTED_TRANSPORT
        Rejected.AppRec -> MessageStatus.REJECTED_APPREC
    }

    private fun NextStateDecision.toErrorPayload(messageId: Uuid): ErrorPayload? = when (this) {
        Rejected.Transport -> ErrorPayload(
            code = "REJECTED_TRANSPORT",
            details = "Transport failed for messageId=$messageId"
        )

        is NextStateDecision.Transition ->
            to.takeIf { it == INVALID }?.let {
                ErrorPayload(
                    code = "INVALID_STATE",
                    details = "Unable to evaluate next state for messageId=$messageId"
                )
            }

        else -> null
    }

    private fun logPublishError(message: MessageState, error: PublishError) {
        when (error) {
            is PublishError.Failure ->
                log.error(error.cause) { error.withMessageContext(message) }
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
            NextStateDecision.Unchanged -> log.debug { message.formatUnchanged() }
        }
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
