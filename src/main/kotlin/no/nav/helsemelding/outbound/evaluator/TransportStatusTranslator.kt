package no.nav.helsemelding.outbound.evaluator

import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.TransportStatus

/** Maps external delivery status to the internal [TransportStatus] used for evaluation. */
class TransportStatusTranslator {
    /**
     * Maps an absent status to NEW, UNCONFIRMED to PENDING and ACKNOWLEDGED to ACKNOWLEDGED.
     * Both REJECTED and ABANDONED resolve to REJECTED for transport evaluation.
     * Transition validation is handled separately by [StateTransitionEvaluator].
     *
     * @param external The external transport status, or `null` if none has been received.
     * @return The normalized transport status used to evaluate the delivery outcome.
     */
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
