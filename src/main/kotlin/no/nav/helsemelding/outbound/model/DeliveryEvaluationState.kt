package no.nav.helsemelding.outbound.model

import no.nav.helsemelding.outbound.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.outbound.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.outbound.model.MessageDeliveryState.NEW
import no.nav.helsemelding.outbound.model.NextStateDecision.Pending.AppRec
import no.nav.helsemelding.outbound.model.NextStateDecision.Pending.Transport
import no.nav.helsemelding.outbound.model.PendingReason.WAITING_FOR_APPREC
import no.nav.helsemelding.outbound.model.PendingReason.WAITING_FOR_TRANSPORT_ACKNOWLEDGEMENT

data class DeliveryEvaluationState(
    val transport: TransportStatus,
    val appRec: AppRecStatus?
)

data class DeliveryResolution(
    val decision: NextStateDecision,
    val pendingReason: PendingReason? = null
)

fun DeliveryEvaluationState.resolveDelivery(): DeliveryResolution =
    when (transport) {
        TransportStatus.NEW -> DeliveryResolution(NextStateDecision.Transition(NEW))
        TransportStatus.PENDING -> DeliveryResolution(
            decision = Transport,
            pendingReason = WAITING_FOR_TRANSPORT_ACKNOWLEDGEMENT
        )

        TransportStatus.ACKNOWLEDGED ->
            when {
                appRec.isNull() -> DeliveryResolution(
                    decision = AppRec,
                    pendingReason = WAITING_FOR_APPREC
                )

                appRec.isRejected() -> DeliveryResolution(NextStateDecision.Rejected.AppRec)
                appRec.isOk() || appRec.isOkErrorInMessagePart() -> DeliveryResolution(
                    NextStateDecision.Transition(
                        COMPLETED
                    )
                )

                else -> error("ACKNOWLEDGED transport but appRec was neither null/rejected/ok: $appRec")
            }

        TransportStatus.REJECTED -> DeliveryResolution(NextStateDecision.Rejected.Transport)
        TransportStatus.INVALID -> DeliveryResolution(NextStateDecision.Transition(INVALID))
    }

fun DeliveryResolution.toDeliveryState(): MessageDeliveryState =
    when (decision) {
        is NextStateDecision.Transition -> decision.to
        is NextStateDecision.Pending -> MessageDeliveryState.PENDING
        is NextStateDecision.Rejected -> MessageDeliveryState.REJECTED
        NextStateDecision.Unchanged -> error("Unchanged is not a resolvable delivery state")
    }
