package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.recover
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.v3.Notification
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.NEW_MESSAGE
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.REFUSED_MESSAGE
import no.nav.helsemelding.outbound.FetchStatusError
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.ErrorPayload
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
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
import no.nav.helsemelding.outbound.repository.NotificationOffsetRepository
import no.nav.helsemelding.outbound.util.translate
import no.nav.helsemelding.outbound.util.withSpan
import no.nav.helsemelding.outbound.withMessageContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("NotificationService")

class NotificationService(
    private val ediAdapterClient: EdiAdapterClient,
    private val messageStateService: MessageStateService,
    private val stateEvaluatorService: StateEvaluatorService,
    private val statusMessagePublisher: MessagePublisher,
    private val notificationOffsetRepository: NotificationOffsetRepository
) {
    suspend fun processNotifications(scope: CoroutineScope): Job {
        val herId = config().ediAdapter.senderHerId.value
        val offset = notificationOffsetRepository.getOffset(herId)
        log.info { "Starting notification stream for herId: $herId from offset: $offset" }
        return ediAdapterClient.streamNotifications(herId, offset)
            .onEach { either ->
                either
                    .onLeft { failure ->
                        throw NotificationProcessingException("Notification stream failed for herId: $herId failure: $failure")
                    }
                    .onRight { notification ->
                        processNotification(notification)
                        notificationOffsetRepository.saveOffset(herId, notification.offset)
                    }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)
    }

    private suspend fun processNotification(notification: Notification) {
        when (notification.type) {
            NEW_MESSAGE, REFUSED_MESSAGE -> Unit
            else ->
                notification.relatedMessageId
                    ?.let { messageStateService.getMessageSnapshotByExternalRefId(it) }
                    ?.takeUnless { stateEvaluatorService.isTerminal(it.messageState) }
                    ?.run {
                        tracer.withSpan("Refresh message status") {
                            log.info {
                                "${messageState.logPrefix()} Processing notification with " +
                                    "id: ${notification.notificationId} type: ${notification.type} offset: ${notification.offset}"
                            }
                            fetchExternalStatus(messageState)
                                .onLeft { failure ->
                                    throw NotificationProcessingException(
                                        "${messageState.logPrefix()} Failed fetching status: $failure"
                                    )
                                }
                                .onRight { processStatus(messageState, it) }
                        }
                    }
        }
    }

    private suspend fun processStatus(message: MessageState, external: ExternalStatus) {
        val decision = determineNextState(message, external)
            .also { logTransition(message, it) }
        when (decision) {
            NextStateDecision.Unchanged -> Unit
            else -> {
                val statusEvent = decision.toStatusEvent(message.id, external)
                statusMessagePublisher.publish(statusEvent)
                    .onLeft { failure ->
                        when (failure) {
                            is PublishError.Failure -> throw NotificationProcessingException(
                                "${message.logPrefix()} Failed publishing status: $failure",
                                failure.cause
                            )
                        }
                    }
                    .onRight { recordStateChange(message, external) }
            }
        }
    }

    private suspend fun fetchExternalStatus(message: MessageState): Either<FetchStatusError, ExternalStatus> = either {
        log.debug { "${message.logPrefix()} Fetching status from edi-adapter" }
        val response = ediAdapterClient.getMessageStatus(message.externalRefId)
            .mapLeft { FetchStatusError.FetchFailure(it) }
            .bind()
        val statuses = response.statusList.orEmpty()
            .also { log.debug { "${message.logPrefix()} Received ${it.size} statuses" } }
        ensure(statuses.size == 1) { FetchStatusError.UnexpectedReceiverCount(statuses.size) }

        statuses.single().translate()
            .also { log.debug { message.formatExternal(it.deliveryState, it.appRecStatus) } }
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

    private fun NextStateDecision.toStatusEvent(messageId: Uuid, external: ExternalStatus): MessageStatusEvent =
        MessageStatusEvent(
            messageId = messageId,
            timestamp = Clock.System.now(),
            status = toMessageStatus(),
            apprec = external.apprec,
            error = toErrorPayload(messageId, external.deliveryState)
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

    private fun NextStateDecision.toErrorPayload(messageId: Uuid, transport: ExternalDeliveryState?): ErrorPayload? =
        when (this) {
            Rejected.Transport -> when (transport) {
                ExternalDeliveryState.ABANDONED -> ErrorPayload(
                    code = "TRANSPORT_ABANDONED",
                    details = "Transport abandoned after failed sending attempts for messageId: $messageId"
                )

                else -> ErrorPayload(
                    code = "REJECTED_TRANSPORT",
                    details = "Transport rejected for messageId: $messageId"
                )
            }

            is NextStateDecision.Transition ->
                to.takeIf { it == INVALID }?.let {
                    ErrorPayload(
                        code = "INVALID_STATE",
                        details = "Unable to evaluate next state for messageId: $messageId"
                    )
                }

            else -> null
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
}

internal class NotificationProcessingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
