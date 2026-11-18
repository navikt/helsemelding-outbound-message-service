package no.nav.helsemelding.outbound.service

import arrow.core.raise.recover
import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.StateTransitionError
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
import no.nav.helsemelding.outbound.model.formatInvalidState
import no.nav.helsemelding.outbound.model.formatTransition
import no.nav.helsemelding.outbound.model.formatUnchanged
import no.nav.helsemelding.outbound.model.logPrefix
import no.nav.helsemelding.outbound.publisher.MessagePublisher
import no.nav.helsemelding.outbound.withMessageContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

class MessageStatusService(
    private val messageStateService: MessageStateService,
    private val stateEvaluatorService: StateEvaluatorService,
    private val statusMessagePublisher: MessagePublisher
) {
    fun isTerminal(message: MessageState): Boolean = stateEvaluatorService.isTerminal(message)

    suspend fun processStatus(message: MessageState, external: ExternalStatus): MessageStatusEvent? {
        if (isTerminal(message)) return null
        val decision = determineNextState(message, external).also { logTransition(message, it) }
        if (decision == NextStateDecision.Unchanged) return null
        val event = decision.toStatusEvent(message.id, external)
        statusMessagePublisher.publish(event)
            .onLeft { failure ->
                when (failure) {
                    is PublishError.Failure -> throw StatusProcessingException(
                        "${message.logPrefix()} Failed publishing status: $failure",
                        failure.cause
                    )
                }
            }
        recordStateChange(message, external)
        return event
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
                    code = "TRANSPORT_REJECTED",
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

internal class StatusProcessingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
