package no.nav.helsemelding.outbound.evaluator

import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.TransportStatus

/**
 * Translates an external transport delivery state into the internal
 * [TransportStatus] representation.
 *
 * This function performs a total, side-effect-free mapping and never fails:
 * all external states (including `null`) are exhaustively handled.
 *
 * - `null` → `NEW`
 * - `UNCONFIRMED` → `PENDING`
 * - `ACKNOWLEDGED` → `ACKNOWLEDGED`
 * - `REJECTED` → `REJECTED`
 *
 * The translator does not perform any validation; it simply normalizes
 * external transport semantics into the domain’s transport lifecycle.
 */
class TransportStatusTranslator {
    fun translate(
        external: ExternalDeliveryState?
    ): TransportStatus = when (external) {
        null -> TransportStatus.NEW
        ExternalDeliveryState.UNCONFIRMED -> TransportStatus.PENDING
        ExternalDeliveryState.ACKNOWLEDGED -> TransportStatus.ACKNOWLEDGED
        ExternalDeliveryState.REJECTED -> TransportStatus.REJECTED
    }
}
