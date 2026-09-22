package no.nav.helsemelding.outbound.evaluator

import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.TransportStatus

/**
 * Maps external delivery status to the internal [TransportStatus] used for evaluation.
 * An absent external status represents a new message. Transition validation is handled
 * separately by [StateTransitionEvaluator].
 */
class TransportStatusTranslator {
    fun translate(
        external: ExternalDeliveryState?
    ): TransportStatus = when (external) {
        null -> TransportStatus.NEW
        ExternalDeliveryState.UNCONFIRMED -> TransportStatus.PENDING
        ExternalDeliveryState.ACKNOWLEDGED -> TransportStatus.ACKNOWLEDGED
        ExternalDeliveryState.ABANDONED,
        ExternalDeliveryState.REJECTED -> TransportStatus.REJECTED
    }
}
